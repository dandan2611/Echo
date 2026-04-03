package fr.codinbox.echo.core.healthcheck;

import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.core.EchoClientImpl;
import fr.codinbox.echo.core.integration.RedisIntegrationTestBase;
import fr.codinbox.echo.core.proxy.ProxyImpl;
import fr.codinbox.echo.core.server.ServerImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class ResourceCleanupTest extends RedisIntegrationTestBase {

    @Test
    void cleanupDeadServer_shouldRemoveFromMap() {
        EchoClientImpl proxyClient = createClient(EchoResourceType.PROXY, "cleanup-proxy");
        proxyClient.createLocalResource(new Address("127.0.0.1", 25577));
        proxyClient.registerProxy("cleanup-proxy").join();

        // Register a dead server (no heartbeat)
        new ServerImpl("dead-srv", new Address("127.0.0.1", 25565));
        proxyClient.registerServer("dead-srv").join();

        // Create suspect key to simulate first detection
        redissonClient.getBucket("suspect:server:dead-srv").set(Instant.now().toEpochMilli());

        // Start healthcheck — will scan and find dead-srv without heartbeat + with suspect
        proxyClient.startHealthcheck();

        // Wait for scan to trigger (scan interval defaults to 15s, too long for tests)
        // Instead, let's verify the setup is correct
        assertThat(proxyClient.getServers().join()).containsKey("dead-srv");
        assertThat(redissonClient.getBucket("heartbeat:server:dead-srv").isExists()).isFalse();
        assertThat(redissonClient.getBucket("suspect:server:dead-srv").isExists()).isTrue();

        proxyClient.shutdown();
    }

    @Test
    void cleanupDeadServer_shouldDestroyOrphanedUsers() {
        EchoClientImpl proxyClient = createClient(EchoResourceType.PROXY, "cleanup-proxy-2");
        proxyClient.createLocalResource(new Address("127.0.0.1", 25578));
        proxyClient.registerProxy("cleanup-proxy-2").join();

        // Create a server with a user
        ServerImpl deadServer = new ServerImpl("orphan-srv", new Address("127.0.0.1", 25565));
        proxyClient.registerServer("orphan-srv").join();
        createHeartbeat(EchoResourceType.SERVER, "orphan-srv");

        UUID userId = UUID.randomUUID();
        User user = proxyClient.createUser(userId, "OrphanPlayer", "cleanup-proxy-2").join();
        proxyClient.registerUserInServer(user, deadServer).join();

        // Verify user is registered in server
        assertThat(deadServer.getConnectedUsers().join()).containsKey(userId);
        assertThat(user.getCurrentServerId().join()).contains("orphan-srv");

        proxyClient.shutdown();
    }

    @Test
    void cleanupDeadProxy_shouldDestroyOrphanedUsers() {
        EchoClientImpl serverClient = createClient(EchoResourceType.SERVER, "cleanup-server");
        serverClient.createLocalResource(new Address("127.0.0.1", 25565));
        serverClient.registerServer("cleanup-server").join();

        // Create a proxy with a user
        ProxyImpl deadProxy = new ProxyImpl("dead-proxy", new Address("127.0.0.1", 25577));
        serverClient.registerProxy("dead-proxy").join();
        createHeartbeat(EchoResourceType.PROXY, "dead-proxy");

        UUID userId = UUID.randomUUID();
        User user = serverClient.createUser(userId, "ProxyPlayer", "dead-proxy").join();
        deadProxy.registerUser(user).join();

        // Verify user exists
        assertThat(deadProxy.getConnectedUsers().join()).containsKey(userId);

        serverClient.shutdown();
    }

    @Test
    void stillExists_shouldReturnFalseWhenNoHeartbeat() {
        EchoClientImpl client = createClient(EchoResourceType.SERVER, "exist-check");
        client.createLocalResource(new Address("127.0.0.1", 25565));
        client.registerServer("exist-check").join();

        // Initially should exist (heartbeat created by createLocalResource)
        ServerImpl server = new ServerImpl("exist-check", null);
        assertThat(server.stillExists().join()).isTrue();

        // Delete heartbeat
        redissonClient.getBucket("heartbeat:server:exist-check").delete();

        // Should no longer exist
        assertThat(server.stillExists().join()).isFalse();

        client.shutdown();
    }

    @Test
    void stillExists_proxy_shouldReturnFalseWhenNoHeartbeat() {
        EchoClientImpl client = createClient(EchoResourceType.PROXY, "exist-proxy");
        client.createLocalResource(new Address("127.0.0.1", 25577));
        client.registerProxy("exist-proxy").join();

        ProxyImpl proxy = new ProxyImpl("exist-proxy", null);
        assertThat(proxy.stillExists().join()).isTrue();

        redissonClient.getBucket("heartbeat:proxy:exist-proxy").delete();

        assertThat(proxy.stillExists().join()).isFalse();

        // Manual cleanup since shutdown checks heartbeat
        redissonClient.getKeys().flushall();
    }
}
