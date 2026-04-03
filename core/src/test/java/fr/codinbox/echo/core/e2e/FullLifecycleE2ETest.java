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

import java.util.Map;
import java.util.Optional;
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
    void fullServerLifecycle() {
        // Create local resource and register server
        client.createLocalResource(new Address("127.0.0.1", 25565));
        client.registerServer("e2e-server").join();

        // Create a user
        UUID userId = UUID.randomUUID();
        User user = client.createUser(userId, "LifecycleUser", "proxy-1").join();

        // Register user in server
        Server server = client.getServerById("e2e-server").join().orElseThrow();
        server.registerUser(user).join();
        user.setProperty(User.PROPERTY_CURRENT_SERVER_ID, server.getId()).join();

        // Verify user is connected
        Map<UUID, Long> connectedUsers = server.getConnectedUsers().join();
        assertThat(connectedUsers).containsKey(userId);

        // Verify user properties
        assertThat(user.getUsername().join()).isPresent().contains("LifecycleUser");
        assertThat(user.getCurrentServerId().join()).isPresent().contains("e2e-server");

        // Destroy user
        client.destroyUser(user).join();

        // Verify user is gone
        Optional<User> found = client.getUserById(userId).join();
        assertThat(found).isEmpty();

        // Unregister server
        client.unregisterServer("e2e-server").join();

        // Verify server is gone from map
        Map<String, Long> servers = client.getServers().join();
        assertThat(servers).doesNotContainKey("e2e-server");
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

        // Register in server A using the actual instance
        client.registerUserInServer(user, srvA).join();

        assertThat(srvA.getConnectedUsers().join()).containsKey(userId);
        assertThat(user.getCurrentServerId().join()).isPresent().contains("srv-a");

        // Switch to server B (auto-unregisters from A)
        client.registerUserInServer(user, srvB).join();

        assertThat(srvB.getConnectedUsers().join()).containsKey(userId);
        assertThat(user.getCurrentServerId().join()).isPresent().contains("srv-b");

        // Verify auto-unregistered from A — re-read from Redis
        Server freshA = client.getServerById("srv-a").join().orElseThrow();
        assertThat(freshA.getConnectedUsers().join()).doesNotContainKey(userId);
    }

    @Test
    void propertyPersistenceAcrossLookups() {
        new ServerImpl("srv-props", new Address("10.0.0.3", 25565));
        client.registerServer("srv-props").join();
        createHeartbeat(EchoResourceType.SERVER, "srv-props");

        UUID userId = UUID.randomUUID();
        User user = client.createUser(userId, "PropUser", "proxy-1").join();

        // Set a custom property
        user.setProperty("custom:level", 99).join();

        // Lookup user again by ID
        User lookedUp = client.getUserById(userId).join().orElseThrow();

        // Verify property persists
        Optional<Integer> level = lookedUp.<Integer>getProperty("custom:level").join();
        assertThat(level).isPresent().contains(99);
    }

    @Test
    void usernameResolution() {
        UUID userId = UUID.randomUUID();
        client.createUser(userId, "Alice", "proxy-1").join();

        // Lookup by username
        Optional<User> found = client.getUserByUsername("Alice").join();
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(userId);

        // Destroy user
        client.destroyUser(found.get()).join();

        // Lookup again should return empty
        Optional<User> afterDestroy = client.getUserByUsername("Alice").join();
        assertThat(afterDestroy).isEmpty();
    }
}
