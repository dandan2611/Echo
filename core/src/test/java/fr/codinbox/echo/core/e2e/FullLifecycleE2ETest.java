package fr.codinbox.echo.core.e2e;

import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.core.EchoClientImpl;
import fr.codinbox.echo.core.integration.RedisIntegrationTestBase;
import fr.codinbox.echo.core.proxy.ProxyImpl;
import fr.codinbox.echo.core.server.ServerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("e2e")
class FullLifecycleE2ETest extends RedisIntegrationTestBase {

    private EchoClientImpl client;

    @BeforeEach
    void setUp() {
        client = createClient(EchoResourceType.SERVER, "e2e-server");
    }

    @Test
    void userServerSwitching() {
        // Create and register two servers with addresses
        ServerImpl srvA = new ServerImpl("srv-a", new Address("10.0.0.1", 25565));
        client.registerServer("srv-a").join();
        createHeartbeat(EchoResourceType.SERVER, "srv-a");

        ServerImpl srvB = new ServerImpl("srv-b", new Address("10.0.0.2", 25565));
        client.registerServer("srv-b").join();
        createHeartbeat(EchoResourceType.SERVER, "srv-b");

        // Create a user
        UUID userId = UUID.randomUUID();
        User user = client.createUser(userId, "Switcher", "proxy-1").join();

        // Register in server A directly
        srvA.registerUser(user).join();
        user.setProperty(User.PROPERTY_CURRENT_SERVER_ID, "srv-a").join();

        assertThat(srvA.getConnectedUsers().join()).containsKey(userId);
        assertThat(user.getCurrentServerId().join()).isPresent().contains("srv-a");

        // Switch to server B (auto-unregisters from A via registerUserInServer)
        client.registerUserInServer(user, srvB).join();

        assertThat(srvB.getConnectedUsers().join()).containsKey(userId);
        assertThat(user.getCurrentServerId().join()).isPresent().contains("srv-b");

        // Verify auto-unregistered from A — re-read from Redis
        Server freshA = client.getServerById("srv-a").join().orElseThrow();
        assertThat(freshA.getConnectedUsers().join()).doesNotContainKey(userId);
    }

}
