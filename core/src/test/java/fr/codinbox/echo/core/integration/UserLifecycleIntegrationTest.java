package fr.codinbox.echo.core.integration;

import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.core.EchoClientImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class UserLifecycleIntegrationTest extends RedisIntegrationTestBase {

    private EchoClientImpl client;

    @BeforeEach
    void setUp() {
        client = createClient(EchoResourceType.SERVER, "test-server");
    }

    @Test
    void createUser_storesUserData() {
        UUID userId = UUID.randomUUID();

        User user = client.createUser(userId, "TestUser", "proxy-1").join();

        Optional<User> found = client.getUserById(userId).join();
        assertThat(found).isPresent();
        assertThat(found.get().getUsername().join()).isPresent().contains("TestUser");
    }

    @Test
    void getUserByUsername_returnsUser() {
        UUID userId = UUID.randomUUID();
        client.createUser(userId, "Bob", "proxy-1").join();

        Optional<User> found = client.getUserByUsername("Bob").join();

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(userId);
    }

    @Test
    void getUserByUsername_caseInsensitive() {
        UUID userId = UUID.randomUUID();
        client.createUser(userId, "Alice", "proxy-1").join();

        Optional<User> found = client.getUserByUsername("alice").join();

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(userId);
    }

    @Test
    void getAllUsers_returnsAllCreated() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        client.createUser(id1, "User1", "proxy-1").join();
        client.createUser(id2, "User2", "proxy-1").join();
        client.createUser(id3, "User3", "proxy-1").join();

        Map<UUID, Long> all = client.getAllUsers().join();

        assertThat(all).hasSize(3);
        assertThat(all).containsKeys(id1, id2, id3);
    }

    @Test
    void destroyUser_removesFromNetwork() {
        UUID userId = UUID.randomUUID();
        User user = client.createUser(userId, "Doomed", "proxy-1").join();

        client.destroyUser(user).join();

        Optional<User> found = client.getUserById(userId).join();
        assertThat(found).isEmpty();
    }
}
