package fr.codinbox.echo.core.user;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.cache.RedisCacheProvider;
import fr.codinbox.echo.core.testutils.EchoTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class UserImplTest {

    @BeforeEach
    void setUp() {
        EchoTestUtils.resetEchoClient();
    }

    @Test
    void constants_shouldHaveCorrectValues() {
        assertThat(UserImpl.USERNAME_TO_ID_MAP).isEqualTo("users:username_to_id");
        assertThat(UserImpl.USER_MAP).isEqualTo("users:map");
        assertThat(UserImpl.USER_KEY_PREFIX).isEqualTo("user:%s");
    }

    @Test
    void getId_shouldReturnTheUuid() {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            UUID uuid = UUID.randomUUID();
            UserImpl user = new UserImpl(uuid);

            assertThat(user.getId()).isEqualTo(uuid);
        }
    }
}
