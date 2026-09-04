package fr.codinbox.echo.core.healthcheck;

import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.core.EchoClientImpl;
import fr.codinbox.echo.core.integration.RedisIntegrationTestBase;
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
