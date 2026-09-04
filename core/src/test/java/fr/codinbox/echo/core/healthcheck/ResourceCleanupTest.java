package fr.codinbox.echo.core.healthcheck;

import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.core.EchoClientImpl;
import fr.codinbox.echo.core.integration.RedisIntegrationTestBase;
import fr.codinbox.echo.core.proxy.ProxyImpl;
import fr.codinbox.echo.core.server.ServerImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class ResourceCleanupTest extends RedisIntegrationTestBase {

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
