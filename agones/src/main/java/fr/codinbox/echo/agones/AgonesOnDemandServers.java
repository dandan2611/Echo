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
import org.jetbrains.annotations.NotNull;

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
import java.util.function.Supplier;

/**
 * Agones-backed implementation of the on-demand server interface.
 */
public final class AgonesOnDemandServers implements OnDemandServers, OnDemandAdministration, AutoCloseable {

    private static final String REQUESTS_MAP = "ondemand:agones:requests";
    private static final String SERVERS_MAP = "ondemand:agones:servers";
    private static final String SERVER_TYPE_LABEL = "echo.codinbox.fr/server-type";
    private static final String REQUEST_HASH_LABEL = "echo.codinbox.fr/request-hash";
    private static final String REQUEST_ID_ANNOTATION = "echo.codinbox.fr/request-id";
    private static final String PENDING_ALLOCATION = "@pending";
    private static final Path SERVICE_ACCOUNT_DIRECTORY =
            Path.of("/var/run/secrets/kubernetes.io/serviceaccount");
    private static final long LOCK_WAIT_SECONDS = 60;
    private static final int KUBERNETES_REQUEST_TIMEOUT_MILLIS = 10_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ResourceDefinitionContext ALLOCATIONS = new ResourceDefinitionContext.Builder()
            .withGroup("allocation.agones.dev")
            .withVersion("v1")
            .withKind("GameServerAllocation")
            .withPlural("gameserverallocations")
            .withNamespaced(true)
            .build();
    private static final ResourceDefinitionContext GAME_SERVERS = new ResourceDefinitionContext.Builder()
            .withGroup("agones.dev")
            .withVersion("v1")
            .withKind("GameServer")
            .withPlural("gameservers")
            .withNamespaced(true)
            .build();
    private static final Executor KUBERNETES_EXECUTOR = runnable ->
            Thread.ofVirtual().name("echo-agones-kubernetes").start(runnable);

    private final EchoClient echo;
    private final KubernetesClient kubernetes;
    private final String namespace;
    private final Map<String, String> selectors;
    private final Duration availabilityTimeout;
    private final Duration pollInterval;
    private final CacheMap<String, String> requests;
    private final CacheMap<String, String> servers;

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

    static Config inClusterConfig(final Map<String, String> environment, final Path serviceAccountDirectory) {
        final String host = required(environment, "KUBERNETES_SERVICE_HOST");
        final String port = environment.getOrDefault(
                "KUBERNETES_SERVICE_PORT_HTTPS",
                environment.getOrDefault("KUBERNETES_SERVICE_PORT", "443"));
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

    static KubernetesClient kubernetesClient(final Config config, final Supplier<String> tokenProvider) {
        return new KubernetesClientBuilder()
                .withConfig(config)
                .withHttpClientBuilderConsumer(builder -> builder.addOrReplaceInterceptor("TOKEN", new Interceptor() {
                    @Override
                    public void before(
                            final BasicBuilder requestBuilder,
                            final HttpRequest request,
                            final RequestTags tags) {
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
        this.echo = Objects.requireNonNull(echo, "echo");
        this.kubernetes = Objects.requireNonNull(kubernetes, "kubernetes");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        if (namespace.isBlank())
            throw new IllegalArgumentException("namespace must not be blank");
        this.selectors = Map.copyOf(Objects.requireNonNull(selectors, "selectors"));
        this.availabilityTimeout = Objects.requireNonNull(availabilityTimeout, "availabilityTimeout");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        final CacheProvider cache = this.echo.getCacheProvider();
        this.requests = Objects.requireNonNull(cache.getMap(REQUESTS_MAP), "requests map");
        this.servers = Objects.requireNonNull(cache.getMap(SERVERS_MAP), "servers map");
        OnDemandServersRegistry.register(this);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull ServerHandle> acquire(final @NotNull ServerRequest request) {
        return allocateOnce(request)
                .thenCompose(handle -> awaitActive(request, handle,
                                System.nanoTime() + availabilityTimeout.toNanos()));
    }

    private CompletableFuture<ServerHandle> allocateOnce(final ServerRequest request) {
        final CacheProvider cache = this.echo.getCacheProvider();
        return cache.withLock(
                        "ondemand:agones:request:" + request.requestId(),
                        LOCK_WAIT_SECONDS,
                        0,
                        TimeUnit.SECONDS,
                        () -> this.requests.getAsync(request.requestId()).thenCompose(existing -> {
                            if (PENDING_ALLOCATION.equals(existing))
                                return recoverAllocation(request).thenCompose(recovered -> recovered
                                        .map(handle -> storeAllocation(request, handle))
                                        .orElseGet(() -> CompletableFuture.failedFuture(new IllegalStateException(
                                                "Allocation outcome is still pending for " + request.requestId()))));
                            if (existing != null)
                                return restoreReverseMapping(request, existing).thenApply(ignored -> null);
                            return recoverAllocation(request).thenCompose(recovered -> recovered
                                    .map(handle -> storeAllocation(request, handle))
                                    .orElseGet(() -> this.requests.putAsync(
                                                    request.requestId(), PENDING_ALLOCATION)
                                            .thenCompose(ignored -> allocatePending(request))));
                        }))
                .thenCompose(acquired -> {
                    if (!acquired)
                        return CompletableFuture.failedFuture(new TimeoutException(
                                "Timed out acquiring allocation lock for " + request.requestId()));
                    return this.requests.getAsync(request.requestId()).thenCompose(serverId ->
                            serverId == null || PENDING_ALLOCATION.equals(serverId)
                            ? CompletableFuture.failedFuture(new IllegalStateException("Allocation completed without a server ID"))
                            : CompletableFuture.completedFuture(new ServerHandle(serverId, request.requestId())));
                });
    }

    private CompletableFuture<Void> allocatePending(final ServerRequest request) {
        return allocateOrRecover(request)
                .thenCompose(handle -> storeAllocation(request, handle))
                .exceptionallyCompose(error -> {
                    if (isAmbiguousAllocationFailure(error))
                        return CompletableFuture.failedFuture(unwrap(error));
                    return this.requests.removeAsync(request.requestId())
                            .handle((ignored, cleanupError) -> {
                                final Throwable cause = unwrap(error);
                                if (cleanupError != null)
                                    cause.addSuppressed(unwrap(cleanupError));
                                throw new CompletionException(cause);
                            });
                });
    }

    private CompletableFuture<ServerHandle> restoreReverseMapping(final ServerRequest request, final String serverId) {
        return this.servers.getAsync(serverId).thenCompose(existingRequestId -> {
            if (existingRequestId != null && !existingRequestId.equals(request.requestId()))
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Server " + serverId + " belongs to request " + existingRequestId));
            final ServerHandle server = new ServerHandle(serverId, request.requestId());
            return verifyOwnership(server, request.requestId(), request.type()).thenCompose(ignored -> existingRequestId == null
                    ? this.servers.putAsync(serverId, request.requestId()).thenApply(stored -> server)
                    : CompletableFuture.completedFuture(server));
        });
    }

    private CompletableFuture<Void> storeAllocation(final ServerRequest request, final ServerHandle handle) {
        return CompletableFuture.allOf(
                        this.requests.putAsync(request.requestId(), handle.id()),
                        this.servers.putAsync(handle.id(), request.requestId()))
                .exceptionallyCompose(error -> cleanupAllocation(handle, request)
                        .handle((ignored, cleanupError) -> {
                            final Throwable cause = unwrap(error);
                            if (cleanupError != null)
                                cause.addSuppressed(unwrap(cleanupError));
                            throw new CompletionException(cause);
                        }));
    }

    private CompletableFuture<ServerHandle> allocate(final ServerRequest request) {
        final Map<String, String> labels = new HashMap<>(this.selectors);
        labels.put(SERVER_TYPE_LABEL, request.type());

        final GenericKubernetesResource allocation = new GenericKubernetesResource();
        allocation.setApiVersion("allocation.agones.dev/v1");
        allocation.setKind("GameServerAllocation");
        allocation.setAdditionalProperty("spec", Map.of(
                "scheduling", "Distributed",
                "selectors", List.of(Map.of("matchLabels", labels)),
                "metadata", Map.of(
                        "labels", Map.of(REQUEST_HASH_LABEL, requestHash(request.requestId())),
                        "annotations", Map.of(REQUEST_ID_ANNOTATION, request.requestId()))));

        return kubernetesCall(() -> this.kubernetes.genericKubernetesResources(ALLOCATIONS)
                        .inNamespace(this.namespace)
                        .resource(allocation)
                        .create())
                .thenCompose(created -> {
                    final JsonNode status = JSON.valueToTree(created.getAdditionalProperties().get("status"));
                    if (!"Allocated".equals(status.path("state").asText()))
                        return CompletableFuture.failedFuture(new AllocationRejectedException(
                                "Agones allocation returned state " + status.path("state").asText()));
                    final String serverId = status.path("gameServerName").asText();
                    if (serverId.isBlank())
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "Agones allocation did not return a GameServer name"));
                    return CompletableFuture.completedFuture(new ServerHandle(serverId, request.requestId()));
                });
    }

    private CompletableFuture<ServerHandle> allocateOrRecover(final ServerRequest request) {
        return allocate(request).exceptionallyCompose(allocationError -> recoverAllocation(request)
                .handle((recovered, recoveryError) -> {
                    if (recoveryError == null && recovered.isPresent())
                        return recovered.orElseThrow();
                    final Throwable cause = unwrap(allocationError);
                    if (recoveryError != null)
                        cause.addSuppressed(unwrap(recoveryError));
                    throw new CompletionException(cause);
                }));
    }

    private CompletableFuture<Optional<ServerHandle>> recoverAllocation(final ServerRequest request) {
        return kubernetesCall(() -> this.kubernetes.genericKubernetesResources(GAME_SERVERS)
                        .inNamespace(this.namespace)
                        .withLabel(REQUEST_HASH_LABEL, requestHash(request.requestId()))
                        .list()
                        .getItems())
                .thenCompose(items -> {
                    ServerHandle recovered = null;
                    for (GenericKubernetesResource item : items) {
                        final JsonNode status = JSON.valueToTree(item.getAdditionalProperties().get("status"));
                        final Map<String, String> annotations = item.getMetadata().getAnnotations();
                        final Map<String, String> labels = item.getMetadata().getLabels();
                        if (!"Allocated".equals(status.path("state").asText())
                                || item.getMetadata().getDeletionTimestamp() != null
                                || annotations == null
                                || !request.requestId().equals(annotations.get(REQUEST_ID_ANNOTATION))
                                || labels == null
                                || !request.type().equals(labels.get(SERVER_TYPE_LABEL)))
                            continue;
                        final String serverId = item.getMetadata().getName();
                        if (serverId == null || serverId.isBlank())
                            continue;
                        if (recovered != null)
                            return CompletableFuture.failedFuture(new IllegalStateException(
                                    "Multiple Agones allocations found for " + request.requestId()));
                        recovered = new ServerHandle(serverId, request.requestId());
                    }
                    return CompletableFuture.completedFuture(Optional.ofNullable(recovered));
                });
    }

    private CompletableFuture<ServerHandle> awaitActive(
            final ServerRequest request,
            final ServerHandle handle,
            final long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos)
            return CompletableFuture.failedFuture(new TimeoutException(
                    "Server " + handle.id() + " did not become active in Echo"));

        return beforeDeadline(this.echo.getServerById(handle.id()), deadlineNanos).thenCompose(server -> {
            if (server.isEmpty())
                return pollAgain(request, handle, deadlineNanos);
            final Server echoServer = server.orElseThrow();
            return beforeDeadline(echoServer.getAvailability(), deadlineNanos)
                    .thenCompose(availability -> availability == ServerAvailability.ACTIVE
                                    ? applyProperties(echoServer, request.properties(), deadlineNanos)
                                            .thenApply(ignored -> handle)
                                    : pollAgain(request, handle, deadlineNanos));
        }).exceptionallyCompose(error -> retryAfterLookupFailure(request, handle, deadlineNanos));
    }

    private CompletableFuture<Void> applyProperties(
            final Server server,
            final Map<? extends PropertyKey<?>, ?> properties,
            final long deadlineNanos) {
        return beforeDeadline(CompletableFuture.allOf(properties.entrySet().stream()
                .map(entry -> setProperty(server, entry.getKey(), entry.getValue()))
                .toArray(CompletableFuture[]::new)), deadlineNanos);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> setProperty(
            final Server server,
            final PropertyKey<?> key,
            final Object value) {
        return server.setProperty((PropertyKey<Object>) key, value);
    }

    private CompletableFuture<ServerHandle> retryAfterLookupFailure(
            final ServerRequest request,
            final ServerHandle handle,
            final long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos)
            return CompletableFuture.failedFuture(new TimeoutException(
                    "Server " + handle.id() + " did not become active in Echo"));
        return pollAgain(request, handle, deadlineNanos);
    }

    private <T> CompletableFuture<T> beforeDeadline(
            final CompletableFuture<T> future,
            final long deadlineNanos) {
        final long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0)
            return CompletableFuture.failedFuture(new TimeoutException("Echo acknowledgement timed out"));
        return future.thenApply(value -> value).orTimeout(remaining, TimeUnit.NANOSECONDS);
    }

    private CompletableFuture<ServerHandle> pollAgain(
            final ServerRequest request,
            final ServerHandle handle,
            final long deadlineNanos) {
        return CompletableFuture.runAsync(
                        () -> {},
                        CompletableFuture.delayedExecutor(this.pollInterval.toMillis(), TimeUnit.MILLISECONDS))
                .thenCompose(ignored -> awaitActive(request, handle, deadlineNanos));
    }

    @Override
    public @NotNull CompletableFuture<Void> terminate(final @NotNull ServerHandle server) {
        if (server.requestId() == null)
            return CompletableFuture.completedFuture(null);
        return this.servers.getAsync(server.id()).thenCompose(requestId -> requestId == null
                || !requestId.equals(server.requestId())
                ? CompletableFuture.completedFuture(null)
                : terminateMapped(server, requestId));
    }

    @Override
    public @NotNull OnDemandAdministration administration() {
        return this;
    }

    @Override
    public @NotNull CompletableFuture<List<Allocation>> listAllocations() {
        return this.requests.readAllAsync().thenApply(allocations -> allocations.entrySet().stream()
                .filter(entry -> !PENDING_ALLOCATION.equals(entry.getValue()))
                .map(entry -> new Allocation(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator.comparing(Allocation::requestId))
                .toList());
    }

    @Override
    public @NotNull CompletableFuture<Optional<Allocation>> getAllocation(final @NotNull String requestId) {
        requireRequestId(requestId);
        return this.requests.getAsync(requestId).thenApply(serverId ->
                serverId == null || PENDING_ALLOCATION.equals(serverId)
                        ? Optional.empty()
                        : Optional.of(new Allocation(requestId, serverId)));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> terminate(final @NotNull String requestId) {
        return getAllocation(requestId).thenCompose(allocation -> allocation
                .map(current -> this.servers.getAsync(current.serverId()).thenCompose(mappedRequestId -> {
                    if (!requestId.equals(mappedRequestId))
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "Allocation mappings disagree for " + requestId));
                    return terminate(new ServerHandle(current.serverId(), requestId)).thenApply(ignored -> true);
                }))
                .orElseGet(() -> CompletableFuture.completedFuture(false)));
    }

    @Override
    public @NotNull CompletableFuture<Optional<Reconciliation>> reconcile(final @NotNull String requestId) {
        return getAllocation(requestId).thenCompose(allocation -> allocation
                .map(current -> this.echo.getServerById(current.serverId()).thenCompose(server -> server
                        .map(echoServer -> echoServer.stillExists().thenCompose(live -> live
                                ? echoServer.getAvailability().thenApply(availability ->
                                        new Reconciliation(current, true, availability))
                                : CompletableFuture.completedFuture(
                                        new Reconciliation(current, false, null))))
                        .orElseGet(() -> CompletableFuture.completedFuture(
                                new Reconciliation(current, false, null))))
                        .thenApply(Optional::of))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())));
    }

    private CompletableFuture<Void> terminateMapped(final ServerHandle server, final String requestId) {
        return this.echo.getCacheProvider().withLock(
                        "ondemand:agones:request:" + requestId,
                        LOCK_WAIT_SECONDS,
                        0,
                        TimeUnit.SECONDS,
                        () -> this.servers.getAsync(server.id()).thenCompose(currentRequestId -> {
                            if (currentRequestId != null && !currentRequestId.equals(requestId))
                                return CompletableFuture.failedFuture(new IllegalStateException(
                                        "Server " + server.id() + " belongs to request " + currentRequestId));
                            if (currentRequestId == null)
                                return CompletableFuture.completedFuture(null);
                            return verifyOwnershipIfPresent(server, requestId, null)
                                    .thenCompose(present -> present
                                            ? deleteGameServer(server)
                                            : CompletableFuture.completedFuture(null))
                                    .thenCompose(ignored -> this.requests.getAsync(requestId))
                                    .thenCompose(currentServerId -> Objects.equals(currentServerId, server.id())
                                            ? this.requests.removeAsync(requestId).thenApply(removed -> null)
                                            : CompletableFuture.completedFuture(null))
                                    .thenCompose(ignored -> this.servers.removeAsync(server.id())
                                            .thenApply(removed -> null));
                        }))
                .thenCompose(acquired -> acquired
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(new TimeoutException(
                                "Timed out acquiring termination lock for " + requestId)));
    }

    private CompletableFuture<Void> cleanupAllocation(final ServerHandle server, final ServerRequest request) {
        return verifyOwnership(server, request.requestId(), request.type())
                .thenCompose(verified -> deleteGameServer(server)
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
                                        if (mappingError != null)
                                            cause.addSuppressed(unwrap(mappingError));
                                        throw new CompletionException(cause);
                                    }
                                    if (mappingError != null)
                                        throw new CompletionException(unwrap(mappingError));
                                    return null;
                                })));
    }

    private CompletableFuture<Void> verifyOwnership(
            final ServerHandle server,
            final String requestId,
            final String expectedType) {
        return verifyOwnershipIfPresent(server, requestId, expectedType).thenCompose(present -> present
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(new IllegalStateException(
                        "GameServer " + server.id() + " is not owned by request " + requestId)));
    }

    private CompletableFuture<Boolean> verifyOwnershipIfPresent(
            final ServerHandle server,
            final String requestId,
            final String expectedType) {
        return kubernetesCall(() -> {
            final GenericKubernetesResource gameServer = this.kubernetes.genericKubernetesResources(GAME_SERVERS)
                    .inNamespace(this.namespace)
                    .withName(server.id())
                    .get();
            if (gameServer == null)
                return false;
            final Map<String, String> annotations = gameServer.getMetadata() == null
                    ? null : gameServer.getMetadata().getAnnotations();
            final Map<String, String> labels = gameServer.getMetadata() == null
                    ? null : gameServer.getMetadata().getLabels();
            final String actualType = labels == null ? null : labels.get(SERVER_TYPE_LABEL);
            if (annotations == null
                    || !requestId.equals(annotations.get(REQUEST_ID_ANNOTATION))
                    || actualType == null
                    || actualType.isBlank()
                    || expectedType != null && !expectedType.equals(actualType))
                throw new IllegalStateException(
                        "GameServer " + server.id() + " is not owned by request " + requestId);
            return true;
        });
    }

    private CompletableFuture<Void> deleteGameServer(final ServerHandle server) {
        return kubernetesCall(() -> {
            try {
                this.kubernetes.genericKubernetesResources(GAME_SERVERS)
                        .inNamespace(this.namespace)
                        .withName(server.id())
                        .delete();
                return null;
            } catch (KubernetesClientException exception) {
                if (exception.getCode() != 404)
                    throw exception;
                return null;
            }
        });
    }

    private static String requestHash(final String requestId) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(requestId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String required(final Map<String, String> values, final String key) {
        final String value = values.get(key);
        if (value == null || value.isBlank())
            throw new IllegalStateException(key + " is not configured");
        return value;
    }

    private static void requireRequestId(final String requestId) {
        if (Objects.requireNonNull(requestId, "requestId").isBlank())
            throw new IllegalArgumentException("requestId must not be blank");
    }

    private static String readServiceAccountFile(final Path path) {
        try {
            return Files.readString(path).trim();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read Kubernetes service-account file " + path, exception);
        }
    }

    private static Throwable unwrap(final Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private static boolean isAmbiguousAllocationFailure(final Throwable error) {
        final Throwable cause = unwrap(error);
        if (cause instanceof AllocationRejectedException)
            return false;
        if (!(cause instanceof KubernetesClientException exception))
            return true;
        final int status = exception.getCode();
        return status <= 0 || status == 429 || status >= 500;
    }

    private static final class AllocationRejectedException extends IllegalStateException {
        private AllocationRejectedException(final String message) {
            super(message);
        }
    }

    private <T> CompletableFuture<T> kubernetesCall(final Supplier<T> call) {
        return CompletableFuture.supplyAsync(call, KUBERNETES_EXECUTOR);
    }

    @Override
    public void close() {
        OnDemandServersRegistry.unregister(this);
        this.kubernetes.close();
    }
}
