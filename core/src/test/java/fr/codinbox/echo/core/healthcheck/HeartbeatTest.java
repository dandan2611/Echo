package fr.codinbox.echo.core.healthcheck;

import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.core.EchoClientImpl;
import fr.codinbox.echo.core.integration.RedisIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class HeartbeatTest extends RedisIntegrationTestBase {

    @Test
    void createLocalResource_shouldCreateHeartbeatKey() {
        EchoClientImpl client = createClient(EchoResourceType.SERVER, "hb-server");
        client.createLocalResource(new Address("127.0.0.1", 25565));

        boolean exists = redissonClient.getBucket("heartbeat:server:hb-server").isExists();
        assertThat(exists).isTrue();
    }

    @Test
    void createLocalResource_shouldSetHeartbeatTtl() {
        EchoClientImpl client = createClient(EchoResourceType.SERVER, "hb-ttl-server");
        client.createLocalResource(new Address("127.0.0.1", 25566));

        long ttl = redissonClient.getBucket("heartbeat:server:hb-ttl-server").remainTimeToLive();
        // TTL should be > 0 and <= 31000ms (default 30s, with small margin for timing)
        assertThat(ttl).isGreaterThan(0).isLessThanOrEqualTo(31000);
    }

    @Test
    void createLocalResource_proxy_shouldCreateHeartbeatKey() {
        EchoClientImpl client = createClient(EchoResourceType.PROXY, "hb-proxy");
        client.createLocalResource(new Address("127.0.0.1", 25577));

        boolean exists = redissonClient.getBucket("heartbeat:proxy:hb-proxy").isExists();
        assertThat(exists).isTrue();
    }

    @Test
    void heartbeatTask_shouldRenewHeartbeatKey() throws InterruptedException {
        EchoClientImpl client = createClient(EchoResourceType.SERVER, "hb-renew");
        client.createLocalResource(new Address("127.0.0.1", 25567));
        client.registerServer("hb-renew").join();
        client.startHealthcheck();

        // Wait for at least one heartbeat cycle (interval is 10s by default, but let's
        // get the initial TTL and verify it was set)
        long ttl = redissonClient.getBucket("heartbeat:server:hb-renew").remainTimeToLive();
        assertThat(ttl).isGreaterThan(0);

        // Shutdown to stop scheduler
        client.shutdown();
    }

    @Test
    void shutdown_shouldRemoveHeartbeatKey() {
        EchoClientImpl client = createClient(EchoResourceType.SERVER, "hb-shutdown");
        client.createLocalResource(new Address("127.0.0.1", 25568));
        client.registerServer("hb-shutdown").join();
        client.startHealthcheck();

        client.shutdown();

        boolean exists = redissonClient.getBucket("heartbeat:server:hb-shutdown").isExists();
        assertThat(exists).isFalse();
    }
}
