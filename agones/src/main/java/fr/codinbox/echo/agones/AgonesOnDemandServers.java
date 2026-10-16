package fr.codinbox.echo.agones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.cache.CacheMap;
import fr.codinbox.echo.api.cache.CacheProvider;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerAvailability;
import fr.codinbox.echo.ondemand.OnDemandAdministration;
import fr.codinbox.echo.ondemand.OnDemandServers;
import fr.codinbox.echo.ondemand.ServerHandle;
import fr.codinbox.echo.ondemand.ServerRequest;
import fr.codinbox.echo.ondemand.internal.OnDemandServersRegistry;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.http.BasicBuilder;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.Interceptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Agones-backed implementation of the on-demand server interface. */
public final class AgonesOnDemandServers implements OnDemandServers, OnDemandAdministration, AutoCloseable {

    private static final @NotNull String REQUESTS_MAP = "ondemand:agones:requests";
    private static final @NotNull String SERVERS_MAP = "ondemand:agones:servers";
    private static final @NotNull String PENDING_SINCE_MAP = "ondemand:agones:pending-since";
    private static final @NotNull String SERVER_TYPE_LABEL = "echo.codinbox.fr/server-type";
    private static final @NotNull String REQUEST_HASH_LABEL = "echo.codinbox.fr/request-hash";
    private static final @NotNull String REQUEST_ID_ANNOTATION = "echo.codinbox.fr/request-id";
    private static final @NotNull String PENDING_ALLOCATION = "@pending";
    /**
     * An allocation call is bounded by the Kubernetes request timeout. Once this much longer has passed without a
     * GameServer carrying the request, the ambiguous allocation never happened and is safe to replay.
     */
    static final @NotNull Duration PENDING_ALLOCATION_TIMEOUT = Duration.ofMinutes(2);

    private static final @NotNull Path SERVICE_ACCOUNT_DIRECTORY =
            Path.of("/var/run/secrets/kubernetes.io/serviceaccount");
    private static final long LOCK_WAIT_SECONDS = 60;
    private static final int KUBERNETES_REQUEST_TIMEOUT_MILLIS = 10_000;
    private static final @NotNull ObjectMapper JSON = new ObjectMapper();
    private static final @NotNull ResourceDefinitionContext ALLOCATIONS = new ResourceDefinitionContext.Builder()
            .withGroup("allocation.agones.dev")
            .withVersion("v1")
            .withKind("GameServerAllocation")
            .withPlural("gameserverallocations")
            .withNamespaced(true)
            .build();
    private static final @NotNull ResourceDefinitionContext GAME_SERVERS = new ResourceDefinitionContext.Builder()
            .withGroup("agones.dev")
            .withVersion("v1")
            .withKind("GameServer")
            .withPlural("gameservers")
            .withNamespaced(true)
            .build();
    private static final @NotNull Executor KUBERNETES_EXECUTOR =
            runnable -> Thread.ofVirtual().name("echo-agones-kubernetes").start(runnable);

    private final @NotNull EchoClient echo;
    private final @NotNull KubernetesClient kubernetes;
    private final @NotNull String namespace;
    private final @NotNull Map<String, String> selectors;
    private final @NotNull Duration availabilityTimeout;
    private final @NotNull Duration pollInterval;
    private final @NotNull CacheMap<String, String> requests;
    private final @NotNull CacheMap<String, String> servers;
    private final @NotNull CacheMap<String, String> pendingSince;
    private final @NotNull LongSupplier clock;

    /**
     * Creates an adapter using the pod service account and Kubernetes environment variables.
     *
     * @param echo Echo client used for idempotency and registration acknowledgement
     * @param selectors labels shared by all allocation requests
     * @param availabilityTimeout maximum wait for the allocated server to become active in Echo
     * @return an in-cluster adapter
     */
    public static @NotNull AgonesOnDemandServers inCluster(
            final @NotNull EchoClient echo,
            final @NotNull Map<String, String> selectors,
            final @NotNull Duration availabilityTimeout) {
        final Config config = inClusterConfig(System.getenv(), SERVICE_ACCOUNT_DIRECTORY);
        final KubernetesClient kubernetes = kubernetesClient(config, config.getOauthTokenProvider()::getToken);
        final String namespace = kubernetes.getNamespace();
        if (namespace == null || namespace.isBlank()) {
            kubernetes.close();
            throw new IllegalStateException("Kubernetes namespace is not configured");
        }
        try {
            return new AgonesOnDemandServers(
                    echo, kubernetes, namespace, selectors, availabilityTimeout, Duration.ofMillis(250));
        } catch (RuntimeException error) {
            kubernetes.close();
            throw error;
        }
    }

    static @NotNull Config inClusterConfig(
            final @NotNull Map<String, String> environment, final @NotNull Path serviceAccountDirectory) {
        final String host = required(environment, "KUBERNETES_SERVICE_HOST");
        final String port = environment.getOrDefault(
                "KUBERNETES_SERVICE_PORT_HTTPS", environment.getOrDefault("KUBERNETES_SERVICE_PORT", "443"));
        final String authority = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        final Path token = serviceAccountDirectory.resolve("token");

        return new ConfigBuilder(Config.empty())
                .withAutoConfigure(false)
                .withMasterUrl("https://" + authority + ":" + port)
                .withNamespace(readServiceAccountFile(serviceAccountDirectory.resolve("namespace")))
                .withCaCertFile(serviceAccountDirectory.resolve("ca.crt").toString())
                .withOauthTokenProvider(() -> readServiceAccountFile(token))
                .withConnectionTimeout(KUBERNETES_REQUEST_TIMEOUT_MILLIS)
                .withRequestTimeout(KUBERNETES_REQUEST_TIMEOUT_MILLIS)
                .withRequestRetryBackoffLimit(0)
                .build();
    }

    static @NotNull KubernetesClient kubernetesClient(
            final @NotNull Config config, final @NotNull Supplier<String> tokenProvider) {
        return new KubernetesClientBuilder()
                .withConfig(config)
                .withHttpClientBuilderConsumer(builder -> builder.addOrReplaceInterceptor("TOKEN", new Interceptor() {
                    @Override
                    public void before(
                            final @NotNull BasicBuilder requestBuilder,
                            final @NotNull HttpRequest request,
                            final @NotNull RequestTags tags) {
                        requestBuilder.setHeader("Authorization", "Bearer " + tokenProvider.get());
                    }
                }))
                .build();
    }

    AgonesOnDemandServers(
            final @NotNull EchoClient echo,
            final @NotNull KubernetesClient kubernetes,
            final @NotNull String namespace,
            final @NotNull Map<String, String> selectors,
            final @NotNull Duration availabilityTimeout,
            final @NotNull Duration pollInterval) {
        this(echo, kubernetes, namespace, selectors, availabilityTimeout, pollInterval, System::currentTimeMillis);
    }

    AgonesOnDemandServers(
            final @NotNull EchoClient echo,
            final @NotNull KubernetesClient kubernetes,
            final @NotNull String namespace,
            final @NotNull Map<String, String> selectors,
            final @NotNull Duration availabilityTimeout,
            final @NotNull Duration pollInterval,
            final @NotNull LongSupplier clock) {
        this.echo = Objects.requireNonNull(echo, "echo");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.kubernetes = Objects.requireNonNull(kubernetes, "kubernetes");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        if (namespace.isBlank()) throw new IllegalArgumentException("namespace must not be blank");
        this.selectors = Map.copyOf(Objects.requireNonNull(selectors, "selectors"));
        this.availabilityTimeout = Objects.requireNonNull(availabilityTimeout, "availabilityTimeout");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        final CacheProvider cache = this.echo.getCacheProvider();
        this.requests = Objects.requireNonNull(cache.getMap(REQUESTS_MAP), "requests map");
        this.servers = Objects.requireNonNull(cache.getMap(SERVERS_MAP), "servers map");
        this.pendingSince = Objects.requireNonNull(cache.getMap(PENDING_SINCE_MAP), "pending-since map");
        OnDemandServersRegistry.register(this);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull ServerHandle> acquire(final @NotNull ServerRequest request) {
        return this.allocateOnce(request)
                .thenCompose(handle ->
                        this.awaitActive(request, handle, System.nanoTime() + this.availabilityTimeout.toNanos()));
    }

    private @NotNull CompletableFuture<ServerHandle> allocateOnce(final @NotNull ServerRequest request) {
        final CacheProvider cache = this.echo.getCacheProvider();
        return cache.withLock(
                        "ondemand:agones:request:" + request.requestId(),
                        LOCK_WAIT_SECONDS,
                        0,
                        TimeUnit.SECONDS,
                        () -> this.requests.getAsync(request.requestId()).thenCompose(existing -> {
                            if (PENDING_ALLOCATION.equals(existing))
                                return this.recoverAllocation(request)
                                        .thenCompose(recovered -> recovered
                                                .map(handle -> this.storeAllocation(request, handle))
                                                .orElseGet(() -> this.replayExpiredPendingAllocation(request)));
                            if (existing != null)
                                return this.restoreReverseMapping(request, existing)
                                        .thenApply(ignored -> null);
                            return this.recoverAllocation(request)
                                    .thenCompose(recovered -> recovered
                                            .map(handle -> this.storeAllocation(request, handle))
                                            .orElseGet(() -> this.markPending(request)
                                                    .thenCompose(ignored -> this.allocatePending(request))));
                        }))
                .thenCompose(acquired -> {
                    if (!acquired)
                        return CompletableFuture.failedFuture(
                                new TimeoutException("Timed out acquiring allocation lock for " + request.requestId()));
                    return this.requests
                            .getAsync(request.requestId())
                            .thenCompose(serverId -> serverId == null || PENDING_ALLOCATION.equals(serverId)
                                    ? CompletableFuture.failedFuture(
                                            new IllegalStateException("Allocation completed without a server ID"))
                                    : CompletableFuture.completedFuture(
                                            new ServerHandle(serverId, request.requestId())));
                });
    }

    private @NotNull CompletableFuture<Void> markPending(final @NotNull ServerRequest request) {
        return CompletableFuture.allOf(
                this.requests.putAsync(request.requestId(), PENDING_ALLOCATION),
                this.pendingSince.putAsync(request.requestId(), Long.toString(this.clock.getAsLong())));
    }

    /**
     * Replays an ambiguous allocation once no GameServer has claimed it for {@link #PENDING_ALLOCATION_TIMEOUT}.
     * Without this, a lobby stopped or a Kubernetes call timing out mid-allocation blocks the request forever.
     */
    private @NotNull CompletableFuture<Void> replayExpiredPendingAllocation(final @NotNull ServerRequest request) {
        final long now = this.clock.getAsLong();
        return this.pendingSince.getAsync(request.requestId()).thenCompose(since -> {
            final Long startedAt = parseMillis(since);
            // Markers written by an older Echo carry no start time: start their timeout now.
            if (startedAt == null)
                return this.pendingSince
                        .putAsync(request.requestId(), Long.toString(now))
                        .thenCompose(ignored -> stillPending(request));
            if (now - startedAt < PENDING_ALLOCATION_TIMEOUT.toMillis()) return stillPending(request);
            return this.markPending(request).thenCompose(ignored -> this.allocatePending(request));
        });
    }

    private static @NotNull CompletableFuture<Void> stillPending(final @NotNull ServerRequest request) {
        return CompletableFuture.failedFuture(
                new IllegalStateException("Allocation outcome is still pending for " + request.requestId()));
    }

    private static @Nullable Long parseMillis(final @Nullable String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private @NotNull CompletableFuture<Void> allocatePending(final @NotNull ServerRequest request) {
        return this.allocateOrRecover(request)
                .thenCompose(handle -> this.storeAllocation(request, handle))
                .exceptionallyCompose(error -> {
                    if (isAmbiguousAllocationFailure(error)) return CompletableFuture.failedFuture(unwrap(error));
                    return CompletableFuture.allOf(
                                    this.requests.removeAsync(request.requestId()),
                                    this.pendingSince.removeAsync(request.requestId()))
                            .handle((ignored, cleanupError) -> {
                                final Throwable cause = unwrap(error);
                                if (cleanupError != null) cause.addSuppressed(unwrap(cleanupError));
                                throw new CompletionException(cause);
                            });
                });
    }

    private @NotNull CompletableFuture<ServerHandle> restoreReverseMapping(
            final @NotNull ServerRequest request, final @NotNull String serverId) {
        return this.servers.getAsync(serverId).thenCompose(existingRequestId -> {
            if (existingRequestId != null && !existingRequestId.equals(request.requestId()))
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Server " + serverId + " belongs to request " + existingRequestId));
            final ServerHandle server = new ServerHandle(serverId, request.requestId());
            return this.verifyOwnership(server, request.requestId(), request.type())
                    .thenCompose(ignored -> existingRequestId == null
                            ? this.servers
                                    .putAsync(serverId, request.requestId())
                                    .thenApply(stored -> server)
                            : CompletableFuture.completedFuture(server));
        });
    }

    private @NotNull CompletableFuture<Void> storeAllocation(
            final @NotNull ServerRequest request, final @NotNull ServerHandle handle) {
        return CompletableFuture.allOf(
                        this.requests.putAsync(request.requestId(), handle.id()),
                        this.servers.putAsync(handle.id(), request.requestId()))
                .exceptionallyCompose(error -> this.cleanupAllocation(handle, request)
                        .handle((ignored, cleanupError) -> {
                            final Throwable cause = unwrap(error);
                            if (cleanupError != null) cause.addSuppressed(unwrap(cleanupError));
                            throw new CompletionException(cause);
                        }))
                // The start time only matters while pending; a leftover entry is harmless.
                .thenCompose(ignored ->
                        this.pendingSince.removeAsync(request.requestId()).<Void>handle((removed, error) -> null));
    }

    private @NotNull CompletableFuture<ServerHandle> allocate(final @NotNull ServerRequest request) {
        final Map<String, String> labels = new HashMap<>(this.selectors);
        labels.put(SERVER_TYPE_LABEL, request.type());

        final GenericKubernetesResource allocation = new GenericKubernetesResource();
        allocation.setApiVersion("allocation.agones.dev/v1");
        allocation.setKind("GameServerAllocation");
        allocation.setAdditionalProperty(
                "spec",
                Map.of(
                        "scheduling", "Distributed",
                        "selectors", List.of(Map.of("matchLabels", labels)),
                        "metadata",
                                Map.of(
                                        "labels", Map.of(REQUEST_HASH_LABEL, requestHash(request.requestId())),
                                        "annotations", Map.of(REQUEST_ID_ANNOTATION, request.requestId()))));

        return this.kubernetesCall(() -> this.kubernetes
                        .genericKubernetesResources(ALLOCATIONS)
                        .inNamespace(this.namespace)
                        .resource(allocation)
                        .create())
                .thenCompose(created -> {
                    final JsonNode status =
                            JSON.valueToTree(created.getAdditionalProperties().get("status"));
                    if (!"Allocated".equals(status.path("state").asText()))
                        return CompletableFuture.failedFuture(
                                new AllocationRejectedException("Agones allocation returned state "
                                        + status.path("state").asText()));
                    final String serverId = status.path("gameServerName").asText();
                    if (serverId.isBlank())
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("Agones allocation did not return a GameServer name"));
                    return CompletableFuture.completedFuture(new ServerHandle(serverId, request.requestId()));
                });
    }

    private @NotNull CompletableFuture<ServerHandle> allocateOrRecover(final @NotNull ServerRequest request) {
        return this.allocate(request)
                .exceptionallyCompose(allocationError -> this.recoverAllocation(request)
                        .handle((recovered, recoveryError) -> {
                            if (recoveryError == null && recovered.isPresent()) return recovered.orElseThrow();
                            final Throwable cause = unwrap(allocationError);
                            if (recoveryError != null) cause.addSuppressed(unwrap(recoveryError));
                            throw new CompletionException(cause);
                        }));
    }

    private @NotNull CompletableFuture<Optional<ServerHandle>> recoverAllocation(final @NotNull ServerRequest request) {
        return this.kubernetesCall(() -> this.kubernetes
                        .genericKubernetesResources(GAME_SERVERS)
                        .inNamespace(this.namespace)
                        .withLabel(REQUEST_HASH_LABEL, requestHash(request.requestId()))
                        .list()
                        .getItems())
                .thenCompose(items -> {
                    ServerHandle recovered = null;
                    for (GenericKubernetesResource item : items) {
                        final JsonNode status =
                                JSON.valueToTree(item.getAdditionalProperties().get("status"));
                        final Map<String, String> annotations =
                                item.getMetadata().getAnnotations();
                        final Map<String, String> labels = item.getMetadata().getLabels();
                        if (!"Allocated".equals(status.path("state").asText())
                                || item.getMetadata().getDeletionTimestamp() != null
                                || annotations == null
                                || !request.requestId().equals(annotations.get(REQUEST_ID_ANNOTATION))
                                || labels == null
                                || !request.type().equals(labels.get(SERVER_TYPE_LABEL))) continue;
                        final String serverId = item.getMetadata().getName();
                        if (serverId == null || serverId.isBlank()) continue;
                        if (recovered != null)
                            return CompletableFuture.failedFuture(new IllegalStateException(
                                    "Multiple Agones allocations found for " + request.requestId()));
                        recovered = new ServerHandle(serverId, request.requestId());
                    }
                    return CompletableFuture.completedFuture(Optional.ofNullable(recovered));
                });
    }

    private @NotNull CompletableFuture<ServerHandle> awaitActive(
            final @NotNull ServerRequest request, final @NotNull ServerHandle handle, final long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos)
            return CompletableFuture.failedFuture(
                    new TimeoutException("Server " + handle.id() + " did not become active in Echo"));

        return this.beforeDeadline(this.echo.getServerById(handle.id()), deadlineNanos)
                .thenCompose(server -> {
                    if (server.isEmpty()) return this.pollAgain(request, handle, deadlineNanos);
                    final Server echoServer = server.orElseThrow();
                    return this.beforeDeadline(echoServer.getAvailability(), deadlineNanos)
                            .thenCompose(availability -> availability == ServerAvailability.ACTIVE
                                    ? this.applyProperties(echoServer, request.properties(), deadlineNanos)
                                            .thenApply(ignored -> handle)
                                    : this.pollAgain(request, handle, deadlineNanos));
                })
                .exceptionallyCompose(error -> System.nanoTime() >= deadlineNanos
                        ? CompletableFuture.failedFuture(
                                new TimeoutException("Server " + handle.id() + " did not become active in Echo"))
                        : this.pollAgain(request, handle, deadlineNanos));
    }

    private @NotNull CompletableFuture<Void> applyProperties(
            final @NotNull Server server,
            final @NotNull Map<? extends PropertyKey<?>, ?> properties,
            final long deadlineNanos) {
        return this.beforeDeadline(
                CompletableFuture.allOf(properties.entrySet().stream()
                        .map(entry -> this.setProperty(server, entry.getKey(), entry.getValue()))
                        .toArray(CompletableFuture[]::new)),
                deadlineNanos);
    }

    @SuppressWarnings("unchecked")
    private @NotNull CompletableFuture<Void> setProperty(
            final @NotNull Server server, final @NotNull PropertyKey<?> key, final @NotNull Object value) {
        return server.setProperty((PropertyKey<Object>) key, value);
    }

    private <T> @NotNull CompletableFuture<T> beforeDeadline(
            final @NotNull CompletableFuture<T> future, final long deadlineNanos) {
        final long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0)
            return CompletableFuture.failedFuture(new TimeoutException("Echo acknowledgement timed out"));
        return future.thenApply(value -> value).orTimeout(remaining, TimeUnit.NANOSECONDS);
    }

    private @NotNull CompletableFuture<ServerHandle> pollAgain(
            final @NotNull ServerRequest request, final @NotNull ServerHandle handle, final long deadlineNanos) {
        return CompletableFuture.runAsync(
                        () -> {},
                        CompletableFuture.delayedExecutor(this.pollInterval.toMillis(), TimeUnit.MILLISECONDS))
                .thenCompose(ignored -> this.awaitActive(request, handle, deadlineNanos));
    }

    @Override
    public @NotNull CompletableFuture<Void> terminate(final @NotNull ServerHandle server) {
        if (server.requestId() == null) return CompletableFuture.completedFuture(null);
        return this.servers
                .getAsync(server.id())
                .thenCompose(requestId -> requestId == null || !requestId.equals(server.requestId())
                        ? CompletableFuture.completedFuture(null)
                        : this.terminateMapped(server, requestId));
    }

    @Override
    public @NotNull OnDemandAdministration administration() {
        return this;
    }

    @Override
    public @NotNull CompletableFuture<List<Allocation>> listAllocations() {
        return this.requests
                .readAllAsync()
                .thenApply(allocations -> allocations.entrySet().stream()
                        .filter(entry -> !PENDING_ALLOCATION.equals(entry.getValue()))
                        .map(entry -> new Allocation(entry.getKey(), entry.getValue()))
                        .sorted(java.util.Comparator.comparing(Allocation::requestId))
                        .toList());
    }

    @Override
    public @NotNull CompletableFuture<Optional<Allocation>> getAllocation(final @NotNull String requestId) {
        requireRequestId(requestId);
        return this.requests
                .getAsync(requestId)
                .thenApply(serverId -> serverId == null || PENDING_ALLOCATION.equals(serverId)
                        ? Optional.empty()
                        : Optional.of(new Allocation(requestId, serverId)));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> terminate(final @NotNull String requestId) {
        return this.getAllocation(requestId)
                .thenCompose(allocation -> allocation
                        .map(current -> this.servers
                                .getAsync(current.serverId())
                                .thenCompose(mappedRequestId -> {
                                    if (!requestId.equals(mappedRequestId))
                                        return CompletableFuture.failedFuture(new IllegalStateException(
                                                "Allocation mappings disagree for " + requestId));
                                    return this.terminate(new ServerHandle(current.serverId(), requestId))
                                            .thenApply(ignored -> true);
                                }))
                        .orElseGet(() -> CompletableFuture.completedFuture(false)));
    }

    @Override
    public @NotNull CompletableFuture<Optional<Reconciliation>> reconcile(final @NotNull String requestId) {
        return this.getAllocation(requestId)
                .thenCompose(allocation -> allocation
                        .map(current -> this.echo
                                .getServerById(current.serverId())
                                .thenCompose(server -> server.map(echoServer -> echoServer
                                                .stillExists()
                                                .thenCompose(live -> live
                                                        ? echoServer
                                                                .getAvailability()
                                                                .thenApply(availability ->
                                                                        new Reconciliation(current, true, availability))
                                                        : CompletableFuture.completedFuture(
                                                                new Reconciliation(current, false, null))))
                                        .orElseGet(() -> CompletableFuture.completedFuture(
                                                new Reconciliation(current, false, null))))
                                .thenApply(Optional::of))
                        .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())));
    }

    private @NotNull CompletableFuture<Void> terminateMapped(
            final @NotNull ServerHandle server, final @NotNull String requestId) {
        return this.echo
                .getCacheProvider()
                .withLock(
                        "ondemand:agones:request:" + requestId,
                        LOCK_WAIT_SECONDS,
                        0,
                        TimeUnit.SECONDS,
                        () -> this.servers.getAsync(server.id()).thenCompose(currentRequestId -> {
                            if (currentRequestId != null && !currentRequestId.equals(requestId))
                                return CompletableFuture.failedFuture(new IllegalStateException(
                                        "Server " + server.id() + " belongs to request " + currentRequestId));
                            if (currentRequestId == null) return CompletableFuture.completedFuture(null);
                            return this.verifyOwnershipIfPresent(server, requestId, null)
                                    .thenCompose(present -> present
                                            ? this.deleteGameServer(server)
                                            : CompletableFuture.completedFuture(null))
                                    .thenCompose(ignored -> this.requests.getAsync(requestId))
                                    .thenCompose(currentServerId -> Objects.equals(currentServerId, server.id())
                                            ? this.requests
                                                    .removeAsync(requestId)
                                                    .thenApply(removed -> null)
                                            : CompletableFuture.completedFuture(null))
                                    .thenCompose(ignored -> this.servers
                                            .removeAsync(server.id())
                                            .thenApply(removed -> null));
                        }))
                .thenCompose(acquired -> acquired
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(
                                new TimeoutException("Timed out acquiring termination lock for " + requestId)));
    }

    private @NotNull CompletableFuture<Void> cleanupAllocation(
            final @NotNull ServerHandle server, final @NotNull ServerRequest request) {
        return this.verifyOwnership(server, request.requestId(), request.type())
                .thenCompose(verified -> this.deleteGameServer(server)
                        .handle((ignored, deletionError) -> deletionError)
                        .thenCompose(deletionError -> (deletionError == null
                                        ? CompletableFuture.allOf(
                                                this.requests.removeAsync(request.requestId()),
                                                this.servers.removeAsync(server.id()))
                                        : CompletableFuture.allOf(
                                                this.requests.putAsync(request.requestId(), server.id()),
                                                this.servers.putAsync(server.id(), request.requestId())))
                                .handle((ignored, mappingError) -> {
                                    if (deletionError != null) {
                                        final Throwable cause = unwrap(deletionError);
                                        if (mappingError != null) cause.addSuppressed(unwrap(mappingError));
                                        throw new CompletionException(cause);
                                    }
                                    if (mappingError != null) throw new CompletionException(unwrap(mappingError));
                                    return null;
                                })));
    }

    private @NotNull CompletableFuture<Void> verifyOwnership(
            final @NotNull ServerHandle server, final @NotNull String requestId, final @NotNull String expectedType) {
        return this.verifyOwnershipIfPresent(server, requestId, expectedType)
                .thenCompose(present -> present
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(new IllegalStateException(
                                "GameServer " + server.id() + " is not owned by request " + requestId)));
    }

    private @NotNull CompletableFuture<Boolean> verifyOwnershipIfPresent(
            final @NotNull ServerHandle server, final @NotNull String requestId, final @NotNull String expectedType) {
        return this.kubernetesCall(() -> {
            final GenericKubernetesResource gameServer = this.kubernetes
                    .genericKubernetesResources(GAME_SERVERS)
                    .inNamespace(this.namespace)
                    .withName(server.id())
                    .get();
            if (gameServer == null) return false;
            final Map<String, String> annotations = gameServer.getMetadata() == null
                    ? null
                    : gameServer.getMetadata().getAnnotations();
            final Map<String, String> labels = gameServer.getMetadata() == null
                    ? null
                    : gameServer.getMetadata().getLabels();
            final String actualType = labels == null ? null : labels.get(SERVER_TYPE_LABEL);
            if (annotations == null
                    || !requestId.equals(annotations.get(REQUEST_ID_ANNOTATION))
                    || actualType == null
                    || actualType.isBlank()
                    || expectedType != null && !expectedType.equals(actualType))
                throw new IllegalStateException("GameServer " + server.id() + " is not owned by request " + requestId);
            return true;
        });
    }

    private @NotNull CompletableFuture<Void> deleteGameServer(final @NotNull ServerHandle server) {
        return this.kubernetesCall(() -> {
            try {
                this.kubernetes
                        .genericKubernetesResources(GAME_SERVERS)
                        .inNamespace(this.namespace)
                        .withName(server.id())
                        .delete();
                return null;
            } catch (KubernetesClientException exception) {
                if (exception.getCode() != 404) throw exception;
                return null;
            }
        });
    }

    private static @NotNull String requestHash(final @NotNull String requestId) {
        try {
            final byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(requestId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static @NotNull String required(final @NotNull Map<String, String> values, final @NotNull String key) {
        final String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(key + " is not configured");
        return value;
    }

    private static void requireRequestId(final @NotNull String requestId) {
        if (Objects.requireNonNull(requestId, "requestId").isBlank())
            throw new IllegalArgumentException("requestId must not be blank");
    }

    private static @NotNull String readServiceAccountFile(final @NotNull Path path) {
        try {
            return Files.readString(path).trim();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read Kubernetes service-account file " + path, exception);
        }
    }

    private static @NotNull Throwable unwrap(final @NotNull Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private static boolean isAmbiguousAllocationFailure(final @NotNull Throwable error) {
        final Throwable cause = unwrap(error);
        if (cause instanceof AllocationRejectedException) return false;
        if (!(cause instanceof KubernetesClientException exception)) return true;
        final int status = exception.getCode();
        return status <= 0 || status == 429 || status >= 500;
    }

    private static final class AllocationRejectedException extends IllegalStateException {
        private AllocationRejectedException(final @NotNull String message) {
            super(message);
        }
    }

    private <T> @NotNull CompletableFuture<T> kubernetesCall(final @NotNull Supplier<T> call) {
        return CompletableFuture.supplyAsync(call, KUBERNETES_EXECUTOR);
    }

    @Override
    public void close() {
        OnDemandServersRegistry.unregister(this);
        this.kubernetes.close();
    }
}
