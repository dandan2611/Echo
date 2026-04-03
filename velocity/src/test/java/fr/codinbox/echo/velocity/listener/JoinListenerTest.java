package fr.codinbox.echo.velocity.listener;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class JoinListenerTest {

    private JoinListener listener;

    @Mock
    private ProxyServer mockProxyServer;
    @Mock
    private EchoClient mockClient;
    @Mock
    private Player mockPlayer;

    private final UUID playerUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new JoinListener(mockProxyServer);
        lenient().when(mockPlayer.getUniqueId()).thenReturn(playerUuid);
        lenient().when(mockPlayer.getUsername()).thenReturn("TestPlayer");
    }

    @Test
    void onLogin_createsUser() throws Exception {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCurrentResourceId()).thenReturn(Optional.of("proxy-1"));
            when(mockClient.createUser(eq(playerUuid), eq("TestPlayer"), eq("proxy-1")))
                    .thenReturn(EchoFuture.completed(mock(User.class)));

            LoginEvent event = mock(LoginEvent.class);
            when(event.getPlayer()).thenReturn(mockPlayer);

            invokePrivate("onLogin", LoginEvent.class, event);

            verify(mockClient).createUser(playerUuid, "TestPlayer", "proxy-1");
        }
    }

    @Test
    void onLogin_noResourceId_returnsEarly() throws Exception {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCurrentResourceId()).thenReturn(Optional.empty());

            LoginEvent event = mock(LoginEvent.class);
            when(event.getPlayer()).thenReturn(mockPlayer);

            invokePrivate("onLogin", LoginEvent.class, event);

            verify(mockClient, never()).createUser(any(), any(), any());
        }
    }

    @Test
    void onDisconnect_destroysUser() throws Exception {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            User mockUser = mock(User.class);
            when(mockClient.getUserById(playerUuid))
                    .thenReturn(EchoFuture.completed(Optional.of(mockUser)));
            when(mockClient.destroyUser(mockUser)).thenReturn(EchoFuture.completed(null));

            DisconnectEvent event = mock(DisconnectEvent.class);
            when(event.getPlayer()).thenReturn(mockPlayer);

            invokePrivate("onDisconnect", DisconnectEvent.class, event);

            Thread.sleep(100);
            verify(mockClient).destroyUser(mockUser);
        }
    }

    @Test
    void onDisconnect_userNotFound_doesNothing() throws Exception {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getUserById(playerUuid))
                    .thenReturn(EchoFuture.completed(Optional.empty()));

            DisconnectEvent event = mock(DisconnectEvent.class);
            when(event.getPlayer()).thenReturn(mockPlayer);

            invokePrivate("onDisconnect", DisconnectEvent.class, event);

            Thread.sleep(100);
            verify(mockClient, never()).destroyUser(any());
        }
    }

    private void invokePrivate(String methodName, Class<?> paramType, Object arg) throws Exception {
        Method method = JoinListener.class.getDeclaredMethod(methodName, paramType);
        method.setAccessible(true);
        method.invoke(listener, arg);
    }
}
