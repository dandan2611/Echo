package fr.codinbox.echo.core.integration;

import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.core.EchoClientImpl;
import fr.codinbox.echo.core.server.ServerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class ServerRegistrationIntegrationTest extends RedisIntegrationTestBase {

    private EchoClientImpl client;

    @BeforeEach
    void setUp() {
        client = createClient(EchoResourceType.SERVER, "test-server");
    }

    @Test
    void registerServer_addsToServerMap() {
        new ServerImpl("srv-1", new Address("127.0.0.1", 25565));
        client.registerServer("srv-1").join();

        Map<String, Long> servers = client.getServers().join();

        assertThat(servers).containsKey("srv-1");
    }

    @Test
    void unregisterServer_removesFromMap() {
        new ServerImpl("srv-2", new Address("127.0.0.1", 25566));
        client.registerServer("srv-2").join();

        client.unregisterServer("srv-2").join();

        Map<String, Long> servers = client.getServers().join();
        assertThat(servers).doesNotContainKey("srv-2");
    }

    @Test
    void getServerById_existing_returnsServer() {
        new ServerImpl("srv-3", new Address("10.0.0.1", 25565));
        client.registerServer("srv-3").join();
        createHeartbeat(EchoResourceType.SERVER, "srv-3");

        Optional<Server> found = client.getServerById("srv-3").join();

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("srv-3");
    }

    @Test
    void getServerById_nonExistent_returnsEmpty() {
        Optional<Server> found = client.getServerById("no-such-server").join();

        assertThat(found).isEmpty();
    }

    @Test
    void server_registerAndGetConnectedUsers() {
        ServerImpl server = new ServerImpl("srv-4", new Address("127.0.0.1", 25567));
        client.registerServer("srv-4").join();
        createHeartbeat(EchoResourceType.SERVER, "srv-4");

        UUID userId = UUID.randomUUID();
        User user = client.createUser(userId, "Player1", "proxy-1").join();

        server.registerUser(user).join();

        Map<UUID, Long> connectedUsers = server.getConnectedUsers().join();
        assertThat(connectedUsers).containsKey(userId);
    }

    @Test
    void server_stillExists_afterCreation_returnsTrue() {
        new ServerImpl("srv-5", new Address("127.0.0.1", 25568));
        client.registerServer("srv-5").join();
        createHeartbeat(EchoResourceType.SERVER, "srv-5");

        Server server = client.getServerById("srv-5").join().orElseThrow();
        boolean exists = server.stillExists().join();

        assertThat(exists).isTrue();
    }
}
