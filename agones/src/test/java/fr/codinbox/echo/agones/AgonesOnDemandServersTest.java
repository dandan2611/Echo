package fr.codinbox.echo.agones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.cache.CacheMap;
import fr.codinbox.echo.api.cache.CacheProvider;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerAvailability;
import fr.codinbox.echo.ondemand.OnDemandAdministration;
import fr.codinbox.echo.ondemand.OnDemandServers;
import fr.codinbox.echo.ondemand.ServerHandle;
import fr.codinbox.echo.ondemand.ServerRequest;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("unit")
class AgonesOnDemandServersTest {

    private @Nullable HttpServer server;
    private @Nullable KubernetesClient kubernetes;
    private @Nullable AgonesOnDemandServers adapter;

    @TempDir
    private @NotNull Path serviceAccountDirectory;

    @AfterEach
    void stopServer() {
        if (this.adapter != null) this.adapter.close();
        else if (this.kubernetes != null) this.kubernetes.close();
        if (this.server != null) this.server.stop(0);
    }

    @Test
    void acquire_allocatesOnceAndReturnsActiveEchoServer() throws Exception {
        AtomicInteger allocations = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        this.startServer(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                respond(
                        exchange,
                        200,
                        exchange.getRequestURI().getPath().endsWith("/match-abc") ? """
                        {"apiVersion":"agones.dev/v1","kind":"GameServer","metadata":{
                        "name":"match-abc","annotations":{"echo.codinbox.fr/request-id":"queue-42"},
                        "labels":{"echo.codinbox.fr/server-type":"bedwars"}}}
                        """ : "{\"items\":[]}");
                return;
            }
            allocations.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 201, "{\"status\":{\"state\":\"Allocated\",\"gameServerName\":\"match-abc\"}}");
        });

        EchoClient echo = this.activeEchoServer("match-abc");
        AgonesOnDemandServers servers = this.adapter(echo, Duration.ofSeconds(1));
        ServerRequest request = new ServerRequest("queue-42", "bedwars");

        assertThat(servers.acquire(request).join()).isEqualTo(new ServerHandle("match-abc", "queue-42"));
        assertThat(servers.acquire(request).join()).isEqualTo(new ServerHandle("match-abc", "queue-42"));
        assertThat(allocations).hasValue(1);
        assertThat(requestBody.get())
                .contains("\"echo.codinbox.fr/server-type\":\"bedwars\"")
                .contains("\"echo.codinbox.fr/request-id\":\"queue-42\"");
    }

    @Test
    void acquire_setsRequestedEchoPropertiesBeforeReturning() throws Exception {
        this.startServer(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 200, "{\"items\":[]}");
                return;
            }
            respond(exchange, 201, "{\"status\":{\"state\":\"Allocated\",\"gameServerName\":\"lobby-owned\"}}");
        });

        EchoClient echo = this.echoWithCache();
        Server active = mock(Server.class);
        PropertyKey<String> owner = new PropertyKey<>("owner");
        when(active.getAvailability()).thenReturn(EchoFuture.completed(ServerAvailability.ACTIVE));
        when(active.setProperty(owner, "team-a")).thenReturn(EchoFuture.completed(null));
        when(echo.getServerById("lobby-owned")).thenReturn(EchoFuture.completed(Optional.of(active)));

        ServerHandle handle = this.adapter(echo, Duration.ofSeconds(1))
                .acquire(new ServerRequest("queue-owned", "lobby", Map.of(owner, "team-a")))
                .join();

        assertThat(handle).isEqualTo(new ServerHandle("lobby-owned", "queue-owned"));
        verify(active).setProperty(owner, "team-a");
    }

    @Test
    void acquire_recoversAllocationCreatedBeforeRedisMapping() throws Exception {
        AtomicInteger allocations = new AtomicInteger();
        this.startServer(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 200, """
                        {"items":[{"metadata":{"name":"match-recovered","annotations":{
                        "echo.codinbox.fr/request-id":"queue-recovered"},"labels":{
                        "echo.codinbox.fr/server-type":"bedwars"}},"status":{"state":"Allocated"}}]}
                        """);
            } else if (exchange.getRequestMethod().equals("POST")) {
                allocations.incrementAndGet();
                respond(exchange, 201, "{\"status\":{\"state\":\"Allocated\",\"gameServerName\":\"match-recovered\"}}");
            } else {
                respond(exchange, 200, "{}");
            }
        });

        ServerHandle handle = this.adapter(this.activeEchoServer("match-recovered"), Duration.ofSeconds(1))
                .acquire(new ServerRequest("queue-recovered", "bedwars"))
                .join();

        assertThat(handle).isEqualTo(new ServerHandle("match-recovered", "queue-recovered"));
        assertThat(allocations).hasValue(0);
    }

    @Test
    void acquire_doesNotReplayAllocationAfterUnauthorizedResponse() throws Exception {
        AtomicInteger allocations = new AtomicInteger();
        this.startServer(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 200, "{\"apiVersion\":\"agones.dev/v1\",\"kind\":\"GameServerList\",\"items\":[]}");
            } else {
                allocations.incrementAndGet();
                respond(exchange, 401, "{}");
            }
        });

        Config config = new ConfigBuilder(Config.empty())
                .withMasterUrl("http://127.0.0.1:" + this.server.getAddress().getPort())
                .withNamespace("games")
                .withTrustCerts(true)
                .withRequestTimeout(10_000)
                .withRequestRetryBackoffLimit(0)
                .build();
        this.kubernetes = AgonesOnDemandServers.kubernetesClient(config, () -> "token");
        this.adapter = new AgonesOnDemandServers(
                this.echoWithCache(), this.kubernetes, "games", Map.of(), Duration.ofSeconds(1), Duration.ofMillis(2));

        assertThatThrownBy(() -> this.adapter
                        .acquire(new ServerRequest("queue-401", "bedwars"))
                        .join())
                .hasRootCauseInstanceOf(io.fabric8.kubernetes.client.KubernetesClientException.class);
        assertThat(allocations).hasValue(1);
    }

    @Test
    void acquire_afterAmbiguousFailureOnlyRecoversExistingAllocation() throws Exception {
        AtomicInteger allocations = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        this.startServer(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                if (recoveries.incrementAndGet() < 3) {
                    respond(
                            exchange,
                            200,
                            "{\"apiVersion\":\"agones.dev/v1\",\"kind\":\"GameServerList\",\"items\":[]}");
                } else {
                    respond(exchange, 200, """
                            {"apiVersion":"agones.dev/v1","kind":"GameServerList","items":[{
                             "metadata":{"name":"match-delayed","annotations":{
                             "echo.codinbox.fr/request-id":"queue-delayed"},"labels":{
                             "echo.codinbox.fr/server-type":"bedwars"}},"status":{"state":"Allocated"}}]}
                            """);
                }
            } else {
                allocations.incrementAndGet();
                respond(exchange, 500, "{}");
            }
        });

        AgonesOnDemandServers adapter = this.adapter(this.activeEchoServer("match-delayed"), Duration.ofSeconds(1));
        ServerRequest request = new ServerRequest("queue-delayed", "bedwars");
        assertThatThrownBy(() -> adapter.acquire(request).join())
                .hasRootCauseInstanceOf(io.fabric8.kubernetes.client.KubernetesClientException.class);

        assertThat(adapter.acquire(request).join()).isEqualTo(new ServerHandle("match-delayed", "queue-delayed"));
        assertThat(allocations).hasValue(1);
    }

    @Test
    void acquire_keepsRecentPendingAllocationWithoutReplaying() throws Exception {
        AtomicInteger allocations = new AtomicInteger();
        this.startEmptyAllocationServer(allocations, "match-replayed");
        AtomicLong now = new AtomicLong(1_000_000);
        Map<String, String> requests = new ConcurrentHashMap<>(Map.of("queue-pending", "@pending"));
        Map<String, String> pendingSince = new ConcurrentHashMap<>(Map.of("queue-pending", Long.toString(now.get())));
        EchoClient echo = this.activeEchoServer(
                "match-replayed", this.mapBackedMock(requests), this.mapBackedMock(), this.mapBackedMock(pendingSince));
        AgonesOnDemandServers adapter = this.adapter(echo, Duration.ofSeconds(1), now::get);

        now.addAndGet(AgonesOnDemandServers.PENDING_ALLOCATION_TIMEOUT.toMillis() - 1);
        assertThatThrownBy(() -> adapter.acquire(new ServerRequest("queue-pending", "bedwars"))
                        .join())
                .hasRootCauseMessage("Allocation outcome is still pending for queue-pending");
        assertThat(allocations).hasValue(0);
        assertThat(requests).containsEntry("queue-pending", "@pending");
    }

    @Test
    void acquire_replaysPendingAllocationOrphanedPastTimeout() throws Exception {
        AtomicInteger allocations = new AtomicInteger();
        this.startEmptyAllocationServer(allocations, "match-replayed");
        AtomicLong now = new AtomicLong(1_000_000);
        Map<String, String> requests = new ConcurrentHashMap<>(Map.of("queue-orphan", "@pending"));
        Map<String, String> pendingSince = new ConcurrentHashMap<>(Map.of("queue-orphan", Long.toString(now.get())));
        EchoClient echo = this.activeEchoServer(
                "match-replayed", this.mapBackedMock(requests), this.mapBackedMock(), this.mapBackedMock(pendingSince));
        AgonesOnDemandServers adapter = this.adapter(echo, Duration.ofSeconds(1), now::get);

        now.addAndGet(AgonesOnDemandServers.PENDING_ALLOCATION_TIMEOUT.toMillis());
        assertThat(adapter.acquire(new ServerRequest("queue-orphan", "bedwars")).join())
                .isEqualTo(new ServerHandle("match-replayed", "queue-orphan"));
        assertThat(allocations).hasValue(1);
        assertThat(requests).containsEntry("queue-orphan", "match-replayed");
        assertThat(pendingSince).doesNotContainKey("queue-orphan");
    }

    @Test
    void acquire_startsTimeoutForLegacyPendingMarkerThenReplays() throws Exception {
        AtomicInteger allocations = new AtomicInteger();
        this.startEmptyAllocationServer(allocations, "match-replayed");
        AtomicLong now = new AtomicLong(1_000_000);
        Map<String, String> requests = new ConcurrentHashMap<>(Map.of("queue-legacy", "@pending"));
        Map<String, String> pendingSince = new ConcurrentHashMap<>();
        EchoClient echo = this.activeEchoServer(
                "match-replayed", this.mapBackedMock(requests), this.mapBackedMock(), this.mapBackedMock(pendingSince));
        AgonesOnDemandServers adapter = this.adapter(echo, Duration.ofSeconds(1), now::get);
        ServerRequest request = new ServerRequest("queue-legacy", "bedwars");

        assertThatThrownBy(() -> adapter.acquire(request).join())
                .hasRootCauseMessage("Allocation outcome is still pending for queue-legacy");
        assertThat(pendingSince).containsEntry("queue-legacy", "1000000");
        assertThat(allocations).hasValue(0);

        now.addAndGet(AgonesOnDemandServers.PENDING_ALLOCATION_TIMEOUT.toMillis());
        assertThat(adapter.acquire(request).join()).isEqualTo(new ServerHandle("match-replayed", "queue-legacy"));
        assertThat(allocations).hasValue(1);
    }

    private void startEmptyAllocationServer(@NotNull AtomicInteger allocations, @NotNull String serverName)
            throws Exception {
        this.startServer(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 200, "{\"apiVersion\":\"agones.dev/v1\",\"kind\":\"GameServerList\",\"items\":[]}");
                return;
            }
            allocations.incrementAndGet();
            respond(exchange, 201, "{\"status\":{\"state\":\"Allocated\",\"gameServerName\":\"" + serverName + "\"}}");
        });
    }

    @Test
    void inClusterConfig_usesOnlyServiceAccountAndRefreshesToken() throws Exception {
        Files.writeString(this.serviceAccountDirectory.resolve("namespace"), "games\n");
        Files.writeString(this.serviceAccountDirectory.resolve("ca.crt"), "certificate");
        Path token = this.serviceAccountDirectory.resolve("token");
        Files.writeString(token, "first-token\n");

        Config config = AgonesOnDemandServers.inClusterConfig(
                Map.of("KUBERNETES_SERVICE_HOST", "10.0.0.1", "KUBERNETES_SERVICE_PORT_HTTPS", "6443"),
                this.serviceAccountDirectory);

        assertThat(config.getMasterUrl()).isEqualTo("https://10.0.0.1:6443/");
        assertThat(config.getNamespace()).isEqualTo("games");
        assertThat(config.getAutoConfigure()).isFalse();
        assertThat(config.getRequestTimeout()).isEqualTo(10_000);
        assertThat(config.getRequestRetryBackoffLimit()).isZero();
        assertThat(config.getOauthTokenProvider().getToken()).isEqualTo("first-token");

        Files.writeString(token, "rotated-token\n");
        assertThat(config.getOauthTokenProvider().getToken()).isEqualTo("rotated-token");
    }

    @Test
    void acquire_repairsMissingReverseMapping() throws Exception {
        this.startServer(exchange -> respond(exchange, 200, """
                {"apiVersion":"agones.dev/v1","kind":"GameServer","metadata":{
                "name":"match-partial","annotations":{"echo.codinbox.fr/request-id":"queue-partial"},
                "labels":{"echo.codinbox.fr/server-type":"bedwars"}}}
                """));
        Map<String, String> requestValues = new ConcurrentHashMap<>(Map.of("queue-partial", "match-partial"));
        Map<String, String> serverValues = new ConcurrentHashMap<>();
        EchoClient echo = this.echoWithCache(this.mapBackedMock(requestValues), this.mapBackedMock(serverValues));
        Server active = mock(Server.class);
        when(active.getAvailability()).thenReturn(EchoFuture.completed(ServerAvailability.ACTIVE));
        when(echo.getServerById("match-partial")).thenReturn(EchoFuture.completed(Optional.of(active)));

        ServerHandle handle = this.adapter(echo, Duration.ofSeconds(1))
                .acquire(new ServerRequest("queue-partial", "bedwars"))
                .join();

        assertThat(handle).isEqualTo(new ServerHandle("match-partial", "queue-partial"));
        assertThat(serverValues).containsEntry("match-partial", "queue-partial");
    }

    @Test
    void acquire_withoutEchoRegistration_keepsAllocationForRetry() throws Exception {
        AtomicInteger deletions = new AtomicInteger();
        AtomicBoolean active = new AtomicBoolean();
        this.startServer(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                respond(
                        exchange,
                        200,
                        exchange.getRequestURI().getPath().endsWith("/match-timeout")
                                ? """
                        {"apiVersion":"agones.dev/v1","kind":"GameServer","metadata":{
                        "name":"match-timeout","annotations":{"echo.codinbox.fr/request-id":"queue-timeout"},
                        "labels":{"echo.codinbox.fr/server-type":"bedwars"}}}
                        """
                                : "{\"apiVersion\":\"agones.dev/v1\",\"kind\":\"GameServerList\",\"items\":[]}");
            } else if (exchange.getRequestMethod().equals("DELETE")) {
                deletions.incrementAndGet();
                respond(exchange, 200, "{}");
            } else {
                respond(exchange, 201, "{\"status\":{\"state\":\"Allocated\",\"gameServerName\":\"match-timeout\"}}");
            }
        });

        Map<String, String> requestValues = new ConcurrentHashMap<>();
        Map<String, String> serverValues = new ConcurrentHashMap<>();
        EchoClient echo = this.echoWithCache(this.mapBackedMock(requestValues), this.mapBackedMock(serverValues));
        Server activeServer = mock(Server.class);
        when(activeServer.getAvailability()).thenReturn(EchoFuture.completed(ServerAvailability.ACTIVE));
        when(echo.getServerById("match-timeout"))
                .thenAnswer(
                        ignored -> EchoFuture.completed(active.get() ? Optional.of(activeServer) : Optional.empty()));
        AgonesOnDemandServers servers = this.adapter(echo, Duration.ofMillis(20));
        ServerRequest request = new ServerRequest("queue-timeout", "bedwars");

        assertThatThrownBy(() -> servers.acquire(request).join())
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(deletions).hasValue(0);
        assertThat(requestValues).containsEntry("queue-timeout", "match-timeout");
        assertThat(serverValues).containsEntry("match-timeout", "queue-timeout");

        active.set(true);
        assertThat(servers.acquire(request).join()).isEqualTo(new ServerHandle("match-timeout", "queue-timeout"));
        assertThat(deletions).hasValue(0);
    }

    @Test
    void acquire_retriesTransientEchoLookupFailure() throws Exception {
        this.startServer(exchange -> {
            if (exchange.getRequestMethod().equals("GET"))
                respond(exchange, 200, "{\"apiVersion\":\"agones.dev/v1\",\"kind\":\"GameServerList\",\"items\":[]}");
            else respond(exchange, 201, "{\"status\":{\"state\":\"Allocated\",\"gameServerName\":\"match-retry\"}}");
        });
        EchoClient echo = this.echoWithCache();
        Server active = mock(Server.class);
        when(active.getAvailability()).thenReturn(EchoFuture.completed(ServerAvailability.ACTIVE));
        EchoFuture<Optional<Server>> failedLookup = new EchoFuture<>();
        failedLookup.completeExceptionally(new IllegalStateException("Redis unavailable"));
        when(echo.getServerById("match-retry")).thenReturn(failedLookup, EchoFuture.completed(Optional.of(active)));

        assertThat(this.adapter(echo, Duration.ofSeconds(1))
                        .acquire(new ServerRequest("queue-retry", "bedwars"))
                        .join())
                .isEqualTo(new ServerHandle("match-retry", "queue-retry"));
    }

    @Test
    void acquire_whenIdempotencyMappingFails_terminatesAllocationAndCleansMappings() throws Exception {
        AtomicInteger deletions = new AtomicInteger();
        this.startServer(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                respond(
                        exchange,
                        200,
                        exchange.getRequestURI().getPath().endsWith("/match-mapping")
                                ? """
                        {"apiVersion":"agones.dev/v1","kind":"GameServer","metadata":{
                        "name":"match-mapping","annotations":{"echo.codinbox.fr/request-id":"queue-mapping"},
                        "labels":{"echo.codinbox.fr/server-type":"bedwars"}}}
                        """
                                : "{\"apiVersion\":\"agones.dev/v1\",\"kind\":\"GameServerList\",\"items\":[]}");
            } else if (exchange.getRequestMethod().equals("DELETE")) {
                deletions.incrementAndGet();
                respond(exchange, 200, "{}");
            } else {
                respond(exchange, 201, "{\"status\":{\"state\":\"Allocated\",\"gameServerName\":\"match-mapping\"}}");
            }
        });

        CacheMap<String, String> requests = this.mapBackedMock();
        CacheMap<String, String> servers = this.mapBackedMock();
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("Redis unavailable")))
                .when(requests)
                .putAsync("queue-mapping", "match-mapping");
        EchoClient echo = this.echoWithCache(requests, servers);

        assertThatThrownBy(() -> this.adapter(echo, Duration.ofSeconds(1))
                        .acquire(new ServerRequest("queue-mapping", "bedwars"))
                        .join())
                .hasRootCauseMessage("Redis unavailable");
        assertThat(deletions).hasValue(1);
        verify(requests).removeAsync("queue-mapping");
        verify(servers).removeAsync("match-mapping");
    }

    @Test
    void terminate_keepsReverseMappingUntilRequestMappingIsRemoved() throws Exception {
        AtomicBoolean deleted = new AtomicBoolean();
        AtomicInteger deletions = new AtomicInteger();
        this.startServer(exchange -> {
            if (exchange.getRequestMethod().equals("DELETE")) {
                deleted.set(true);
                deletions.incrementAndGet();
                respond(exchange, 200, "{}");
            } else if (deleted.get()) {
                respond(exchange, 404, "{}");
            } else {
                respond(exchange, 200, """
                        {"apiVersion":"agones.dev/v1","kind":"GameServer","metadata":{
                        "name":"match-terminate","annotations":{"echo.codinbox.fr/request-id":"queue-terminate"},
                        "labels":{"echo.codinbox.fr/server-type":"bedwars"}}}
                        """);
            }
        });
        Map<String, String> requestValues = new ConcurrentHashMap<>(Map.of("queue-terminate", "match-terminate"));
        Map<String, String> serverValues = new ConcurrentHashMap<>(Map.of("match-terminate", "queue-terminate"));
        CacheMap<String, String> requests = this.mapBackedMock(requestValues);
        CacheMap<String, String> servers = this.mapBackedMock(serverValues);
        AtomicInteger removals = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> removals.incrementAndGet() == 1
                        ? CompletableFuture.failedFuture(new IllegalStateException("Redis unavailable"))
                        : CompletableFuture.completedFuture(requestValues.remove("queue-terminate")))
                .when(requests)
                .removeAsync("queue-terminate");
        AgonesOnDemandServers adapter = this.adapter(this.echoWithCache(requests, servers), Duration.ofSeconds(1));
        ServerHandle handle = new ServerHandle("match-terminate", "queue-terminate");

        assertThatThrownBy(() -> adapter.terminate(handle).join()).hasRootCauseMessage("Redis unavailable");
        assertThat(serverValues).containsEntry("match-terminate", "queue-terminate");

        adapter.terminate(handle).join();
        assertThat(requestValues).isEmpty();
        assertThat(serverValues).isEmpty();
        assertThat(deletions).hasValue(1);
    }

    @Test
    void terminate_doesNotRemoveAReplacementAllocation() {
        Map<String, String> requestValues = new ConcurrentHashMap<>(Map.of("queue-reused", "match-new"));
        CacheMap<String, String> requests = this.mapBackedMock(requestValues);
        CacheMap<String, String> servers = this.mapBackedMock();
        when(servers.getAsync("match-old"))
                .thenReturn(CompletableFuture.completedFuture("queue-reused"), CompletableFuture.completedFuture(null));

        this.adapter(this.echoWithCache(requests, servers), mock(KubernetesClient.class))
                .terminate(new ServerHandle("match-old", "queue-reused"))
                .join();

        assertThat(requestValues).containsEntry("queue-reused", "match-new");
    }

    @Test
    void terminate_arbitraryHandleIsIdempotentNoOp() {
        AtomicInteger deletions = new AtomicInteger();
        KubernetesClient kubernetes = this.gameServerClient("untrusted-name", "other-request", "bedwars", deletions);
        AgonesOnDemandServers adapter = this.adapter(this.echoWithCache(), kubernetes);

        adapter.terminate(new ServerHandle("untrusted-name")).join();
        adapter.terminate(new ServerHandle("untrusted-name")).join();

        assertThat(deletions).hasValue(0);
        verify(kubernetes, never()).genericKubernetesResources(any());
    }

    @Test
    void terminate_staleHandleDoesNotDeleteReusedServerName() {
        Map<String, String> requestValues = new ConcurrentHashMap<>(Map.of("queue-new", "match-reused"));
        Map<String, String> serverValues = new ConcurrentHashMap<>(Map.of("match-reused", "queue-new"));
        KubernetesClient kubernetes = mock(KubernetesClient.class);
        AgonesOnDemandServers adapter = this.adapter(
                this.echoWithCache(this.mapBackedMock(requestValues), this.mapBackedMock(serverValues)), kubernetes);

        adapter.terminate(new ServerHandle("match-reused", "queue-old")).join();

        assertThat(requestValues).containsEntry("queue-new", "match-reused");
        assertThat(serverValues).containsEntry("match-reused", "queue-new");
        verify(kubernetes, never()).genericKubernetesResources(any());
    }

    @Test
    void terminate_missingReverseMappingDoesNotDeleteKnownAllocation() {
        AtomicInteger deletions = new AtomicInteger();
        Map<String, String> requestValues = new ConcurrentHashMap<>(Map.of("queue-known", "match-known"));
        KubernetesClient kubernetes = this.gameServerClient("match-known", "queue-known", "bedwars", deletions);
        AgonesOnDemandServers adapter =
                this.adapter(this.echoWithCache(this.mapBackedMock(requestValues), this.mapBackedMock()), kubernetes);

        adapter.terminate(new ServerHandle("match-known", "queue-known")).join();

        assertThat(requestValues).containsEntry("queue-known", "match-known");
        assertThat(deletions).hasValue(0);
        verify(kubernetes, never()).genericKubernetesResources(any());
    }

    @Test
    void terminate_rejectsRecycledServerNameWithoutMutatingOwnership() {
        AtomicInteger deletions = new AtomicInteger();
        Map<String, String> requestValues = new ConcurrentHashMap<>(Map.of("queue-old", "match-recycled"));
        Map<String, String> serverValues = new ConcurrentHashMap<>(Map.of("match-recycled", "queue-old"));
        AgonesOnDemandServers adapter = this.adapter(
                this.echoWithCache(this.mapBackedMock(requestValues), this.mapBackedMock(serverValues)),
                this.gameServerClient("match-recycled", "queue-new", "bedwars", deletions));

        assertThatThrownBy(() -> adapter.terminate(new ServerHandle("match-recycled", "queue-old"))
                        .join())
                .hasRootCauseMessage("GameServer match-recycled is not owned by request queue-old");
        assertThat(requestValues).containsEntry("queue-old", "match-recycled");
        assertThat(serverValues).containsEntry("match-recycled", "queue-old");
        assertThat(deletions).hasValue(0);
    }

    @Test
    void acquire_rejectsServerTypeMismatchWithoutRepairingReverseMapping() {
        Map<String, String> requestValues = new ConcurrentHashMap<>(Map.of("queue-owned", "match-recycled"));
        Map<String, String> serverValues = new ConcurrentHashMap<>();
        EchoClient echo = this.echoWithCache(this.mapBackedMock(requestValues), this.mapBackedMock(serverValues));
        Server active = mock(Server.class);
        when(active.getAvailability()).thenReturn(EchoFuture.completed(ServerAvailability.ACTIVE));
        when(echo.getServerById("match-recycled")).thenReturn(EchoFuture.completed(Optional.of(active)));
        AgonesOnDemandServers adapter = this.adapter(
                echo, this.gameServerClient("match-recycled", "queue-owned", "skywars", new AtomicInteger()));

        assertThatThrownBy(() -> adapter.acquire(new ServerRequest("queue-owned", "bedwars"))
                        .join())
                .hasRootCauseMessage("GameServer match-recycled is not owned by request queue-owned");
        assertThat(serverValues).isEmpty();
    }

    @Test
    void terminate_deletesValidOwnedServerAndMappings() {
        AtomicInteger deletions = new AtomicInteger();
        Map<String, String> requestValues = new ConcurrentHashMap<>(Map.of("queue-owned", "match-owned"));
        Map<String, String> serverValues = new ConcurrentHashMap<>(Map.of("match-owned", "queue-owned"));
        AgonesOnDemandServers adapter = this.adapter(
                this.echoWithCache(this.mapBackedMock(requestValues), this.mapBackedMock(serverValues)),
                this.gameServerClient("match-owned", "queue-owned", "bedwars", deletions));

        adapter.terminate(new ServerHandle("match-owned", "queue-owned")).join();

        assertThat(requestValues).isEmpty();
        assertThat(serverValues).isEmpty();
        assertThat(deletions).hasValue(1);
    }

    @Test
    void lifecycleRegistersOneAdapterAndCloseUnregistersByIdentity() {
        KubernetesClient firstClient = mock(KubernetesClient.class);
        KubernetesClient rejectedClient = mock(KubernetesClient.class);
        KubernetesClient replacementClient = mock(KubernetesClient.class);
        this.adapter = new AgonesOnDemandServers(
                this.echoWithCache(), firstClient, "games", Map.of(), Duration.ofSeconds(1), Duration.ofMillis(2));

        assertThat(OnDemandServers.load()).isSameAs(this.adapter);
        assertThatThrownBy(() -> new AgonesOnDemandServers(
                        this.echoWithCache(),
                        rejectedClient,
                        "games",
                        Map.of(),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OnDemandServers is already loaded");
        assertThat(OnDemandServers.load()).isSameAs(this.adapter);

        AgonesOnDemandServers first = this.adapter;
        first.close();
        this.adapter = new AgonesOnDemandServers(
                this.echoWithCache(),
                replacementClient,
                "games",
                Map.of(),
                Duration.ofSeconds(1),
                Duration.ofMillis(2));
        first.close();
        assertThat(OnDemandServers.load()).isSameAs(this.adapter);
        verify(firstClient, org.mockito.Mockito.times(2)).close();
        verify(rejectedClient, never()).close();
        rejectedClient.close();
    }

    @Test
    void administrationListsAndLooksUpSharedAllocations() {
        Map<String, String> requestValues = new ConcurrentHashMap<>(Map.of(
                "queue-b", "match-b",
                "queue-pending", "@pending",
                "queue-a", "match-a"));
        this.adapter = new AgonesOnDemandServers(
                this.echoWithCache(this.mapBackedMock(requestValues), this.mapBackedMock()),
                mock(KubernetesClient.class),
                "games",
                Map.of(),
                Duration.ofSeconds(1),
                Duration.ofMillis(2));
        OnDemandAdministration administration = this.adapter.administration();

        List<OnDemandAdministration.Allocation> allocations =
                administration.listAllocations().join();
        assertThat(allocations)
                .containsExactly(
                        new OnDemandAdministration.Allocation("queue-a", "match-a"),
                        new OnDemandAdministration.Allocation("queue-b", "match-b"));
        assertThatThrownBy(() -> allocations.add(new OnDemandAdministration.Allocation("queue-c", "match-c")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(administration.getAllocation("queue-b").join())
                .contains(new OnDemandAdministration.Allocation("queue-b", "match-b"));
        assertThat(administration.getAllocation("missing").join()).isEmpty();
        assertThat(administration.getAllocation("queue-pending").join()).isEmpty();
        assertThatThrownBy(() -> administration.getAllocation(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("requestId");
        assertThatThrownBy(() -> administration.getAllocation(" \t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestId must not be blank");
    }

    @Test
    void administrationTerminatesOnlyVerifiedMappedAllocation() {
        Map<String, String> requestValues = new ConcurrentHashMap<>(Map.of(
                "queue-good", "match-good",
                "queue-bad", "match-bad",
                "queue-pending", "@pending"));
        Map<String, String> serverValues = new ConcurrentHashMap<>(Map.of(
                "match-good", "queue-good",
                "match-bad", "another-request"));
        KubernetesClient kubernetes = mock(KubernetesClient.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>>
                resources = mock(MixedOperation.class);
        NonNamespaceOperation<
                        GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>>
                namespaced = mock(NonNamespaceOperation.class);
        Resource<GenericKubernetesResource> resource = mock(Resource.class);
        when(kubernetes.genericKubernetesResources(
                        any(io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext.class)))
                .thenReturn(resources);
        when(resources.inNamespace("games")).thenReturn(namespaced);
        when(namespaced.withName("match-good")).thenReturn(resource);
        when(resource.get()).thenReturn(gameServer("match-good", "queue-good", "bedwars"));
        this.adapter = new AgonesOnDemandServers(
                this.echoWithCache(this.mapBackedMock(requestValues), this.mapBackedMock(serverValues)),
                kubernetes,
                "games",
                Map.of(),
                Duration.ofSeconds(1),
                Duration.ofMillis(2));
        OnDemandAdministration administration = this.adapter.administration();

        assertThat(administration.terminate("queue-good").join()).isTrue();
        assertThat(requestValues).doesNotContainKey("queue-good");
        assertThat(serverValues).doesNotContainKey("match-good");
        assertThat(administration.terminate("missing").join()).isFalse();
        assertThat(administration.terminate("queue-pending").join()).isFalse();
        assertThatThrownBy(() -> administration.terminate("queue-bad").join())
                .hasRootCauseMessage("Allocation mappings disagree for queue-bad");
        assertThat(requestValues).containsEntry("queue-bad", "match-bad");
        assertThat(serverValues).containsEntry("match-bad", "another-request");
        verify(namespaced, org.mockito.Mockito.times(2)).withName("match-good");
        verify(resource).delete();
        verify(namespaced, never()).withName("match-bad");
        verify(namespaced, never()).withName("@pending");
    }

    @Test
    void reconcileReportsMissingLiveAndDeadAllocationsWithoutKubernetesCalls() {
        Map<String, String> requestValues = new ConcurrentHashMap<>(Map.of(
                "queue-active", "match-active",
                "queue-draining", "match-draining",
                "queue-dead", "match-dead",
                "queue-gone", "match-gone",
                "queue-pending", "@pending"));
        EchoClient echo = this.echoWithCache(this.mapBackedMock(requestValues), this.mapBackedMock());
        Server active = echoServer(true, ServerAvailability.ACTIVE);
        Server draining = echoServer(true, ServerAvailability.DRAINING);
        Server dead = echoServer(false, null);
        when(echo.getServerById("match-active")).thenReturn(EchoFuture.completed(Optional.of(active)));
        when(echo.getServerById("match-draining")).thenReturn(EchoFuture.completed(Optional.of(draining)));
        when(echo.getServerById("match-dead")).thenReturn(EchoFuture.completed(Optional.of(dead)));
        when(echo.getServerById("match-gone")).thenReturn(EchoFuture.completed(Optional.empty()));
        KubernetesClient kubernetes = mock(KubernetesClient.class);
        this.adapter = new AgonesOnDemandServers(
                echo, kubernetes, "games", Map.of(), Duration.ofSeconds(1), Duration.ofMillis(2));

        assertThat(this.adapter.reconcile("queue-active").join())
                .contains(new OnDemandAdministration.Reconciliation(
                        new OnDemandAdministration.Allocation("queue-active", "match-active"),
                        true,
                        ServerAvailability.ACTIVE));
        assertThat(this.adapter.reconcile("queue-draining").join())
                .contains(new OnDemandAdministration.Reconciliation(
                        new OnDemandAdministration.Allocation("queue-draining", "match-draining"),
                        true,
                        ServerAvailability.DRAINING));
        assertThat(this.adapter.reconcile("queue-dead").join())
                .contains(new OnDemandAdministration.Reconciliation(
                        new OnDemandAdministration.Allocation("queue-dead", "match-dead"), false, null));
        assertThat(this.adapter.reconcile("queue-gone").join())
                .contains(new OnDemandAdministration.Reconciliation(
                        new OnDemandAdministration.Allocation("queue-gone", "match-gone"), false, null));
        assertThat(this.adapter.reconcile("missing").join()).isEmpty();
        assertThat(this.adapter.reconcile("queue-pending").join()).isEmpty();
        verify(dead, never()).getAvailability();
        verify(echo, never()).getServerById("@pending");
        verify(kubernetes, never()).genericKubernetesResources(any());
    }

    @Test
    void reconcilePropagatesEchoLookupAndLivenessFailures() {
        Map<String, String> requestValues = new ConcurrentHashMap<>(Map.of(
                "queue-lookup", "match-lookup",
                "queue-liveness", "match-liveness",
                "queue-availability", "match-availability"));
        EchoClient echo = this.echoWithCache(this.mapBackedMock(requestValues), this.mapBackedMock());
        Server livenessFailure = mock(Server.class);
        Server availabilityFailure = mock(Server.class);
        when(echo.getServerById("match-lookup")).thenReturn(failedEchoFuture("lookup failed"));
        when(echo.getServerById("match-liveness")).thenReturn(EchoFuture.completed(Optional.of(livenessFailure)));
        when(echo.getServerById("match-availability"))
                .thenReturn(EchoFuture.completed(Optional.of(availabilityFailure)));
        when(livenessFailure.stillExists()).thenReturn(failedEchoFuture("liveness failed"));
        when(availabilityFailure.stillExists()).thenReturn(EchoFuture.completed(true));
        when(availabilityFailure.getAvailability()).thenReturn(failedEchoFuture("availability failed"));
        this.adapter = new AgonesOnDemandServers(
                echo, mock(KubernetesClient.class), "games", Map.of(), Duration.ofSeconds(1), Duration.ofMillis(2));

        assertThatThrownBy(() -> this.adapter.reconcile("queue-lookup").join()).hasRootCauseMessage("lookup failed");
        assertThatThrownBy(() -> this.adapter.reconcile("queue-liveness").join())
                .hasRootCauseMessage("liveness failed");
        assertThatThrownBy(() -> this.adapter.reconcile("queue-availability").join())
                .hasRootCauseMessage("availability failed");
    }

    private @NotNull AgonesOnDemandServers adapter(@NotNull EchoClient echo, @NotNull Duration timeout) {
        return this.adapter(echo, timeout, System::currentTimeMillis);
    }

    private @NotNull AgonesOnDemandServers adapter(
            @NotNull EchoClient echo, @NotNull Duration timeout, @NotNull LongSupplier clock) {
        Config config = new ConfigBuilder(Config.empty())
                .withMasterUrl("http://127.0.0.1:" + this.server.getAddress().getPort())
                .withNamespace("games")
                .withTrustCerts(true)
                .withRequestTimeout(10_000)
                .withRequestRetryBackoffLimit(0)
                .build();
        this.kubernetes = AgonesOnDemandServers.kubernetesClient(config, () -> "token");
        this.adapter = new AgonesOnDemandServers(
                echo,
                this.kubernetes,
                "games",
                Map.of("echo.codinbox.fr/role", "game-server"),
                timeout,
                Duration.ofMillis(2),
                clock);
        return this.adapter;
    }

    private @NotNull AgonesOnDemandServers adapter(@NotNull EchoClient echo, @NotNull KubernetesClient kubernetes) {
        this.kubernetes = kubernetes;
        this.adapter = new AgonesOnDemandServers(
                echo, kubernetes, "games", Map.of(), Duration.ofSeconds(1), Duration.ofMillis(2));
        return this.adapter;
    }

    @SuppressWarnings("unchecked")
    private @NotNull KubernetesClient gameServerClient(
            @NotNull String name, @NotNull String requestId, @NotNull String type, @NotNull AtomicInteger deletions) {
        KubernetesClient kubernetes = mock(KubernetesClient.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>>
                resources = mock(MixedOperation.class);
        NonNamespaceOperation<
                        GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>>
                namespaced = mock(NonNamespaceOperation.class);
        Resource<GenericKubernetesResource> resource = mock(Resource.class);
        when(kubernetes.genericKubernetesResources(
                        any(io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext.class)))
                .thenReturn(resources);
        when(resources.inNamespace("games")).thenReturn(namespaced);
        when(namespaced.withName(name)).thenReturn(resource);
        AtomicBoolean fetched = new AtomicBoolean();
        when(resource.get()).thenAnswer(invocation -> {
            fetched.set(true);
            return gameServer(name, requestId, type);
        });
        org.mockito.Mockito.doAnswer(invocation -> {
                    if (!fetched.get())
                        throw new AssertionError("GameServer ownership was not fetched before deletion");
                    deletions.incrementAndGet();
                    return null;
                })
                .when(resource)
                .delete();
        return kubernetes;
    }

    private static @NotNull GenericKubernetesResource gameServer(
            @NotNull String name, @NotNull String requestId, @NotNull String type) {
        GenericKubernetesResource gameServer = new GenericKubernetesResource();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(name);
        metadata.setAnnotations(Map.of("echo.codinbox.fr/request-id", requestId));
        metadata.setLabels(Map.of("echo.codinbox.fr/server-type", type));
        gameServer.setMetadata(metadata);
        return gameServer;
    }

    private @NotNull EchoClient activeEchoServer(@NotNull String id) {
        return this.activeEchoServer(id, this.mapBackedMock(), this.mapBackedMock(), this.mapBackedMock());
    }

    private @NotNull EchoClient activeEchoServer(
            @NotNull String id,
            @NotNull CacheMap<String, String> requests,
            @NotNull CacheMap<String, String> servers,
            @NotNull CacheMap<String, String> pendingSince) {
        EchoClient echo = this.echoWithCache(requests, servers, pendingSince);
        Server active = mock(Server.class);
        when(active.getAvailability()).thenReturn(EchoFuture.completed(ServerAvailability.ACTIVE));
        when(echo.getServerById(id)).thenReturn(EchoFuture.completed(Optional.of(active)));
        return echo;
    }

    private static @NotNull Server echoServer(boolean live, @NotNull ServerAvailability availability) {
        Server server = mock(Server.class);
        when(server.stillExists()).thenReturn(EchoFuture.completed(live));
        if (availability != null) when(server.getAvailability()).thenReturn(EchoFuture.completed(availability));
        return server;
    }

    private static <T> @NotNull EchoFuture<T> failedEchoFuture(@NotNull String message) {
        EchoFuture<T> future = new EchoFuture<>();
        future.completeExceptionally(new IllegalStateException(message));
        return future;
    }

    @SuppressWarnings("unchecked")
    private @NotNull EchoClient echoWithCache() {
        CacheMap<String, String> requests = this.mapBackedMock();
        CacheMap<String, String> servers = this.mapBackedMock();
        return this.echoWithCache(requests, servers);
    }

    @SuppressWarnings("unchecked")
    private @NotNull EchoClient echoWithCache(
            @NotNull CacheMap<String, String> requests, @NotNull CacheMap<String, String> servers) {
        return this.echoWithCache(requests, servers, this.mapBackedMock());
    }

    @SuppressWarnings("unchecked")
    private @NotNull EchoClient echoWithCache(
            @NotNull CacheMap<String, String> requests,
            @NotNull CacheMap<String, String> servers,
            @NotNull CacheMap<String, String> pendingSince) {
        EchoClient echo = mock(EchoClient.class);
        CacheProvider cache = mock(CacheProvider.class);
        when(echo.getCacheProvider()).thenReturn(cache);
        when(cache.<String, String>getMap("ondemand:agones:requests")).thenReturn(requests);
        when(cache.<String, String>getMap("ondemand:agones:servers")).thenReturn(servers);
        when(cache.<String, String>getMap("ondemand:agones:pending-since")).thenReturn(pendingSince);
        when(cache.withLock(anyString(), anyLong(), anyLong(), any(TimeUnit.class), any()))
                .thenAnswer(invocation -> ((Supplier<CompletableFuture<Void>>) invocation.getArgument(4))
                        .get()
                        .thenApply(ignored -> true));
        return echo;
    }

    @SuppressWarnings("unchecked")
    private @NotNull CacheMap<String, String> mapBackedMock() {
        return this.mapBackedMock(new ConcurrentHashMap<>());
    }

    @SuppressWarnings("unchecked")
    private @NotNull CacheMap<String, String> mapBackedMock(@NotNull Map<String, String> values) {
        CacheMap<String, String> map = mock(CacheMap.class);
        when(map.readAllAsync()).thenAnswer(i -> CompletableFuture.completedFuture(Map.copyOf(values)));
        when(map.getAsync(anyString()))
                .thenAnswer(i -> CompletableFuture.completedFuture(values.get(i.getArgument(0))));
        when(map.putAsync(anyString(), anyString()))
                .thenAnswer(i -> CompletableFuture.completedFuture(values.put(i.getArgument(0), i.getArgument(1))));
        when(map.removeAsync(anyString()))
                .thenAnswer(i -> CompletableFuture.completedFuture(values.remove(i.getArgument(0))));
        return map;
    }

    private void startServer(com.sun.net.httpserver.@NotNull HttpHandler handler) throws Exception {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", handler);
        this.server.start();
    }

    private static void respond(com.sun.net.httpserver.@NotNull HttpExchange exchange, int status, @NotNull String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
