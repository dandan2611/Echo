package fr.codinbox.echo.core.healthcheck;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.core.EchoClientImpl;
import fr.codinbox.echo.core.server.ServerImpl;
import fr.codinbox.echo.core.testutils.EchoTestUtils;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Multi-node integration tests for the healthcheck system.
 * Uses short intervals (TTL=3s, heartbeat=1s, scan=2s) for fast testing.
 */
@Testcontainers
@Tag("integration")
class HealthcheckIntegrationTest {

    private static final long TEST_HEARTBEAT_TTL = 3;
    private static final long TEST_HEARTBEAT_INTERVAL = 1;
    private static final long TEST_SCAN_INTERVAL = 2;

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine")
                    .withExposedPorts(6379);

    private static RedissonClient redissonClient;
    private static RedisConnection mockConnection;
    private final List<EchoClientImpl> activeClients = new ArrayList<>();

    @BeforeAll
    static void setupRedisson() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redissonClient = Redisson.create(config);
        mockConnection = mock(RedisConnection.class);
        when(mockConnection.getClient()).thenReturn(redissonClient);
    }

    @BeforeEach
    void reset() {
        activeClients.clear();
        EchoTestUtils.resetEchoClient();
        redissonClient.getKeys().flushall();
    }

    @AfterEach
    void shutdownClients() {
        // Stop all scheduler threads to prevent interference with other test classes
        for (EchoClientImpl client : activeClients) {
            try {
                client.shutdown();
            } catch (Exception ignored) {
            }
        }
        activeClients.clear();
        EchoTestUtils.resetEchoClient();
    }

    @AfterAll
    static void teardown() {
        if (redissonClient != null && !redissonClient.isShutdown()) {
            redissonClient.shutdown();
        }
    }

    private EchoClientImpl createTestClient(EchoResourceType type, String id, boolean cleanupEnabled) {
        EchoClientImpl client = new EchoClientImpl(mockConnection, type, id,
                TEST_HEARTBEAT_TTL, TEST_HEARTBEAT_INTERVAL, TEST_SCAN_INTERVAL, cleanupEnabled);
        activeClients.add(client);
        return client;
    }

    /**
     * Scenario: 2 servers, 1 proxy.
     * Proxy does the cleanup. One server crashes -> proxy detects and cleans up.
     */
    @Test
    void scenario_twoServers_oneProxy_serverCrash() throws InterruptedException {
        // Create proxy (will do scanning/cleanup)
        EchoClientImpl proxyClient = createTestClient(EchoResourceType.PROXY, "proxy-1", true);
        proxyClient.createLocalResource(new Address("127.0.0.1", 25577));
        proxyClient.registerProxy("proxy-1").join();

        // Create server-1 (stays alive)
        EchoTestUtils.resetEchoClient();
        EchoClientImpl server1 = createTestClient(EchoResourceType.SERVER, "server-1", false);
        server1.createLocalResource(new Address("127.0.0.1", 25565));
        server1.registerServer("server-1").join();
        server1.startHealthcheck();

        // Create server-2 (will "crash")
        EchoTestUtils.resetEchoClient();
        EchoClientImpl server2 = createTestClient(EchoResourceType.SERVER, "server-2", false);
        server2.createLocalResource(new Address("127.0.0.1", 25566));
        server2.registerServer("server-2").join();
        // Don't start heartbeat for server-2

        // Verify both servers registered
        assertThat(proxyClient.getServers().join()).containsKeys("server-1", "server-2");

        // Delete server-2 heartbeat to simulate TTL expiration
        redissonClient.getBucket("heartbeat:server:server-2").delete();

        // Start proxy scanner
        proxyClient.startHealthcheck();

        // Wait for suspect + confirm (2 scan cycles of 2s each + margin)
        Thread.sleep(6000);

        // Verify server-2 was cleaned up
        EchoTestUtils.resetEchoClient();
        EchoClientImpl check = createTestClient(EchoResourceType.PROXY, "proxy-1", false);
        Map<String, Long> servers = check.getServers().join();
        assertThat(servers).containsKey("server-1");
        assertThat(servers).doesNotContainKey("server-2");


    }

    /**
     * Scenario: 2 servers, 0 proxy.
     * Both servers have CLEANUP_ENABLED. One crashes -> the other detects and cleans up.
     */
    @Test
    void scenario_twoServers_noProxy_serverCrash() throws InterruptedException {
        // Create server-1 (will scan and cleanup)
        EchoClientImpl server1 = createTestClient(EchoResourceType.SERVER, "srv-a", true);
        server1.createLocalResource(new Address("127.0.0.1", 25565));
        server1.registerServer("srv-a").join();
        server1.startHealthcheck();

        // Create server-2 (will crash)
        EchoTestUtils.resetEchoClient();
        EchoClientImpl server2 = createTestClient(EchoResourceType.SERVER, "srv-b", false);
        server2.createLocalResource(new Address("127.0.0.1", 25566));
        server2.registerServer("srv-b").join();

        // Delete server-2 heartbeat
        redissonClient.getBucket("heartbeat:server:srv-b").delete();

        // Wait for suspect + confirm
        Thread.sleep(6000);

        EchoTestUtils.resetEchoClient();
        EchoClientImpl check = createTestClient(EchoResourceType.SERVER, "srv-a", false);
        Map<String, Long> servers = check.getServers().join();
        assertThat(servers).containsKey("srv-a");
        assertThat(servers).doesNotContainKey("srv-b");


    }

    /**
     * Scenario: 1 server, 2 proxies with cleanup.
     * Server crashes -> only one proxy does the cleanup thanks to distributed lock.
     */
    @Test
    void scenario_oneServer_twoProxies_lockContention() throws InterruptedException {
        // Create both proxies
        EchoClientImpl proxy1 = createTestClient(EchoResourceType.PROXY, "prx-1", true);
        proxy1.createLocalResource(new Address("127.0.0.1", 25577));
        proxy1.registerProxy("prx-1").join();

        EchoTestUtils.resetEchoClient();
        EchoClientImpl proxy2 = createTestClient(EchoResourceType.PROXY, "prx-2", true);
        proxy2.createLocalResource(new Address("127.0.0.1", 25578));
        proxy2.registerProxy("prx-2").join();

        // Create server that will crash
        EchoTestUtils.resetEchoClient();
        EchoClientImpl server = createTestClient(EchoResourceType.SERVER, "dead-srv", false);
        server.createLocalResource(new Address("127.0.0.1", 25565));
        server.registerServer("dead-srv").join();

        // Delete heartbeat
        redissonClient.getBucket("heartbeat:server:dead-srv").delete();

        // Start both scanners
        proxy1.startHealthcheck();
        proxy2.startHealthcheck();

        // Wait for cleanup
        Thread.sleep(6000);

        // Verify server was cleaned up (only once — no crash from double cleanup)
        EchoTestUtils.resetEchoClient();
        EchoClientImpl check = createTestClient(EchoResourceType.PROXY, "prx-1", false);
        Map<String, Long> servers = check.getServers().join();
        assertThat(servers).doesNotContainKey("dead-srv");


    }

    /**
     * Scenario: Proxy crashes -> server with cleanup cleans it up and destroys its users.
     */
    @Test
    void scenario_proxyCrash_serverCleansUpUsers() throws InterruptedException {
        // Create server with cleanup enabled
        EchoClientImpl serverClient = createTestClient(EchoResourceType.SERVER, "srv-cleanup", true);
        serverClient.createLocalResource(new Address("127.0.0.1", 25565));
        serverClient.registerServer("srv-cleanup").join();

        // Create dead proxy
        EchoTestUtils.resetEchoClient();
        EchoClientImpl proxyClient = createTestClient(EchoResourceType.PROXY, "dead-proxy", false);
        proxyClient.createLocalResource(new Address("127.0.0.1", 25577));
        proxyClient.registerProxy("dead-proxy").join();

        // Create user on the dead proxy
        UUID userId = UUID.randomUUID();
        User user = proxyClient.createUser(userId, "OrphanPlayer", "dead-proxy").join();
        new fr.codinbox.echo.core.proxy.ProxyImpl("dead-proxy", null).registerUser(user).join();

        // Delete proxy heartbeat
        redissonClient.getBucket("heartbeat:proxy:dead-proxy").delete();

        // Start server scanner
        serverClient.startHealthcheck();

        // Wait for cleanup
        Thread.sleep(6000);

        // Verify proxy cleaned up
        EchoTestUtils.resetEchoClient();
        EchoClientImpl check = createTestClient(EchoResourceType.SERVER, "srv-cleanup", false);
        Map<String, Long> proxies = check.getProxies().join();
        assertThat(proxies).doesNotContainKey("dead-proxy");

        // Verify user was destroyed
        Optional<User> userOpt = check.getUserById(userId).join();
        assertThat(userOpt).isEmpty();


    }

    /**
     * Scenario: Server crash with players.
     * Orphaned users (current_server_id = dead server) are destroyed.
     * Redirected users (current_server_id = different server) are preserved.
     */
    @Test
    void scenario_serverCrash_orphanedVsRedirectedUsers() throws InterruptedException {
        // Create proxy
        EchoClientImpl proxyClient = createTestClient(EchoResourceType.PROXY, "prx-users", true);
        proxyClient.createLocalResource(new Address("127.0.0.1", 25577));
        proxyClient.registerProxy("prx-users").join();

        // Create dead server
        EchoTestUtils.resetEchoClient();
        EchoClientImpl deadServer = createTestClient(EchoResourceType.SERVER, "dead-srv-u", false);
        deadServer.createLocalResource(new Address("127.0.0.1", 25565));
        deadServer.registerServer("dead-srv-u").join();

        // Create alive server
        EchoTestUtils.resetEchoClient();
        EchoClientImpl aliveServer = createTestClient(EchoResourceType.SERVER, "alive-srv-u", false);
        aliveServer.createLocalResource(new Address("127.0.0.1", 25566));
        aliveServer.registerServer("alive-srv-u").join();
        aliveServer.startHealthcheck();

        // Create orphaned user
        EchoTestUtils.resetEchoClient();
        EchoClientImpl setupClient = createTestClient(EchoResourceType.PROXY, "prx-users", false);
        UUID orphanId = UUID.randomUUID();
        User orphanUser = setupClient.createUser(orphanId, "OrphanUser", "prx-users").join();
        ServerImpl deadSrvRef = new ServerImpl("dead-srv-u", null);
        deadSrvRef.registerUser(orphanUser).join();
        orphanUser.setProperty("current_server_id", "dead-srv-u").join();

        // Create redirected user (registered in dead server's user map, but current_server_id points elsewhere)
        UUID redirectedId = UUID.randomUUID();
        User redirectedUser = setupClient.createUser(redirectedId, "RedirectedUser", "prx-users").join();
        deadSrvRef.registerUser(redirectedUser).join();
        redirectedUser.setProperty("current_server_id", "alive-srv-u").join();

        // Delete dead server heartbeat
        redissonClient.getBucket("heartbeat:server:dead-srv-u").delete();

        // Start proxy scanner
        proxyClient.startHealthcheck();

        // Wait for cleanup
        Thread.sleep(6000);

        // Verify orphaned user was destroyed
        EchoTestUtils.resetEchoClient();
        EchoClientImpl check = createTestClient(EchoResourceType.PROXY, "prx-users", false);
        Optional<User> orphanOpt = check.getUserById(orphanId).join();
        assertThat(orphanOpt).isEmpty();

        // Verify redirected user is preserved
        Optional<User> redirectedOpt = check.getUserById(redirectedId).join();
        assertThat(redirectedOpt).isPresent();


    }

    /**
     * Scenario: False positive.
     * Server marked suspect, heartbeat returns before second scan -> no cleanup.
     * Uses direct scan calls for deterministic testing.
     */
    @Test
    void scenario_falsePositive_heartbeatReturns() {
        // Create proxy (cleanup enabled, no scheduler — we call scan manually)
        EchoClientImpl proxyClient = createTestClient(EchoResourceType.PROXY, "prx-fp", true);
        proxyClient.createLocalResource(new Address("127.0.0.1", 25577));
        proxyClient.registerProxy("prx-fp").join();

        // Create server
        EchoTestUtils.resetEchoClient();
        EchoClientImpl serverClient = createTestClient(EchoResourceType.SERVER, "fp-srv", false);
        serverClient.createLocalResource(new Address("127.0.0.1", 25565));
        serverClient.registerServer("fp-srv").join();

        // Delete heartbeat to simulate temporary loss
        redissonClient.getBucket("heartbeat:server:fp-srv").delete();

        // First scan: server should be marked as suspect
        proxyClient.scanForDeadResources();
        assertThat(redissonClient.getBucket("suspect:server:fp-srv").isExists())
                .as("suspect key should exist after first scan")
                .isTrue();

        // Server is still in the map (not cleaned up yet)
        assertThat(proxyClient.getServers().join()).containsKey("fp-srv");

        // Restore heartbeat (simulates the server recovering)
        redissonClient.getBucket("heartbeat:server:fp-srv").set(Instant.now().toEpochMilli());
        redissonClient.getBucket("heartbeat:server:fp-srv").expire(Duration.ofSeconds(60));

        // Second scan: heartbeat is back, so server should NOT be cleaned up
        proxyClient.scanForDeadResources();

        // Server should still be in the map
        assertThat(proxyClient.getServers().join()).containsKey("fp-srv");


    }
}
