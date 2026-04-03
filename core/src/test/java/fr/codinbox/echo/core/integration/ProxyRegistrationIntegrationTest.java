package fr.codinbox.echo.core.integration;

import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.core.EchoClientImpl;
import fr.codinbox.echo.core.proxy.ProxyImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class ProxyRegistrationIntegrationTest extends RedisIntegrationTestBase {

    private EchoClientImpl client;

    @BeforeEach
    void setUp() {
        client = createClient(EchoResourceType.PROXY, "test-proxy");
    }

    @Test
    void registerProxy_addsToProxyMap() {
        new ProxyImpl("prx-1", new Address("127.0.0.1", 25577));
        client.registerProxy("prx-1").join();

        Map<String, Long> proxies = client.getProxies().join();

        assertThat(proxies).containsKey("prx-1");
    }

    @Test
    void unregisterProxy_removesFromMap() {
        new ProxyImpl("prx-2", new Address("127.0.0.1", 25578));
        client.registerProxy("prx-2").join();

        client.unregisterProxy("prx-2").join();

        Map<String, Long> proxies = client.getProxies().join();
        assertThat(proxies).doesNotContainKey("prx-2");
    }

    @Test
    void proxy_stillExists_withHeartbeat_returnsTrue() {
        new ProxyImpl("prx-3", new Address("127.0.0.1", 25579));
        client.registerProxy("prx-3").join();
        createHeartbeat(EchoResourceType.PROXY, "prx-3");

        Proxy proxy = client.getProxyById("prx-3").join().orElseThrow();
        boolean exists = proxy.stillExists().join();

        assertThat(exists).isTrue();
    }

    @Test
    void proxy_registerAndGetConnectedUsers() {
        new ProxyImpl("prx-4", new Address("127.0.0.1", 25580));
        client.registerProxy("prx-4").join();
        createHeartbeat(EchoResourceType.PROXY, "prx-4");

        UUID userId = UUID.randomUUID();
        User user = client.createUser(userId, "ProxyPlayer", "prx-4").join();

        Proxy proxy = client.getProxyById("prx-4").join().orElseThrow();
        proxy.registerUser(user).join();

        Map<UUID, Long> connectedUsers = proxy.getConnectedUsers().join();
        assertThat(connectedUsers).containsKey(userId);
    }
}
