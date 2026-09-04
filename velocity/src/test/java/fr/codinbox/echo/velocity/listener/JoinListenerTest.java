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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
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
    private ConcurrentMap<UUID, String> userSessions;

    private final UUID playerUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userSessions = new ConcurrentHashMap<>();
        listener = new JoinListener(() -> true, userSessions, () -> "session-1");
        lenient().when(mockPlayer.getUniqueId()).thenReturn(playerUuid);
        lenient().when(mockPlayer.getUsername()).thenReturn("TestPlayer");
    }

    @Test
    void onLogin_whenDraining_deniesLoginWithoutCreatingUser() throws Exception {
        listener = new JoinListener(() -> false);
        LoginEvent event = mock(LoginEvent.class);

        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            invokePrivate("onLogin", LoginEvent.class, event);

            verify(event).setResult(any(LoginEvent.ComponentResult.class));
            echoMock.verifyNoInteractions();
        }
    }

    @Test
    void onLogin_createsUser() throws Exception {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCurrentResourceId()).thenReturn(Optional.of("proxy-1"));
            when(mockClient.createUser(eq(playerUuid), eq("TestPlayer"), eq("proxy-1"), eq("session-1")))
                    .thenReturn(EchoFuture.completed(mock(User.class)));

            LoginEvent event = mock(LoginEvent.class);
            when(event.getPlayer()).thenReturn(mockPlayer);

            invokePrivate("onLogin", LoginEvent.class, event);

            verify(mockClient).createUser(playerUuid, "TestPlayer", "proxy-1", "session-1");
            assertThat(userSessions).containsEntry(playerUuid, "session-1");
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

            verify(mockClient, never()).createUser(any(), any(), any(), any());
        }
    }

    @Test
    void onDisconnect_destroysUser() throws Exception {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            User mockUser = mock(User.class);
            when(mockClient.getCurrentResourceId()).thenReturn(Optional.of("proxy-1"));
            when(mockClient.createUser(playerUuid, "TestPlayer", "proxy-1", "session-1"))
                    .thenReturn(EchoFuture.completed(mockUser));
            LoginEvent login = mock(LoginEvent.class);
            when(login.getPlayer()).thenReturn(mockPlayer);
            DisconnectEvent event = mock(DisconnectEvent.class);
            when(event.getPlayer()).thenReturn(mockPlayer);

            invokePrivate("onLogin", LoginEvent.class, login);
            invokePrivate("onDisconnect", DisconnectEvent.class, event);

            verify(mockClient).destroyUser(mockUser, "session-1");
            assertThat(userSessions).doesNotContainKey(playerUuid);
        }
    }

    @Test
    void onDisconnect_userNotFound_doesNothing() throws Exception {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCurrentResourceId()).thenReturn(Optional.of("proxy-1"));
            EchoFuture<User> creation = new EchoFuture<>();
            creation.completeExceptionally(new IllegalStateException("create failed"));
            when(mockClient.createUser(playerUuid, "TestPlayer", "proxy-1", "session-1"))
                    .thenReturn(creation);

            LoginEvent login = mock(LoginEvent.class);
            when(login.getPlayer()).thenReturn(mockPlayer);
            DisconnectEvent event = mock(DisconnectEvent.class);
            when(event.getPlayer()).thenReturn(mockPlayer);

            invokePrivate("onLogin", LoginEvent.class, login);
            invokePrivate("onDisconnect", DisconnectEvent.class, event);

            verify(mockClient, never()).destroyUser(any(), any());
        }
    }

    @Test
    void disconnectWaitsForUserCreationBeforeDestroyingTheSession() throws Exception {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCurrentResourceId()).thenReturn(Optional.of("proxy-1"));
            EchoFuture<User> creation = new EchoFuture<>();
            when(mockClient.createUser(playerUuid, "TestPlayer", "proxy-1", "session-1"))
                    .thenReturn(creation);
            User user = mock(User.class);
            LoginEvent login = mock(LoginEvent.class);
            when(login.getPlayer()).thenReturn(mockPlayer);
            DisconnectEvent disconnect = mock(DisconnectEvent.class);
            when(disconnect.getPlayer()).thenReturn(mockPlayer);

            invokePrivate("onLogin", LoginEvent.class, login);
            invokePrivate("onDisconnect", DisconnectEvent.class, disconnect);
            verify(mockClient, never()).destroyUser(any(), any());

            creation.complete(user);
            verify(mockClient).destroyUser(user, "session-1");
        }
    }

    @Test
    void delayedDisconnectAfterReconnectKeepsReplacementSession() throws Exception {
        Player replacement = mock(Player.class);
        when(replacement.getUniqueId()).thenReturn(playerUuid);
        when(replacement.getUsername()).thenReturn("TestPlayer");
        AtomicInteger token = new AtomicInteger();
        listener = new JoinListener(() -> true, userSessions,
                () -> "session-" + token.incrementAndGet());

        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCurrentResourceId()).thenReturn(Optional.of("proxy-1"));
            User firstUser = mock(User.class);
            User replacementUser = mock(User.class);
            when(mockClient.createUser(eq(playerUuid), eq("TestPlayer"), eq("proxy-1"), any()))
                    .thenReturn(EchoFuture.completed(firstUser), EchoFuture.completed(replacementUser));

            LoginEvent firstLogin = mock(LoginEvent.class);
            when(firstLogin.getPlayer()).thenReturn(mockPlayer);
            LoginEvent replacementLogin = mock(LoginEvent.class);
            when(replacementLogin.getPlayer()).thenReturn(replacement);
            DisconnectEvent delayedDisconnect = mock(DisconnectEvent.class);
            when(delayedDisconnect.getPlayer()).thenReturn(mockPlayer);

            invokePrivate("onLogin", LoginEvent.class, firstLogin);
            invokePrivate("onLogin", LoginEvent.class, replacementLogin);
            invokePrivate("onDisconnect", DisconnectEvent.class, delayedDisconnect);

            assertThat(userSessions).containsEntry(playerUuid, "session-2");
            verify(mockClient).destroyUser(firstUser, "session-1");
            verify(mockClient, never()).destroyUser(replacementUser, "session-1");
        }
    }

    private void invokePrivate(String methodName, Class<?> paramType, Object arg) throws Exception {
        Method method = JoinListener.class.getDeclaredMethod(methodName, paramType);
        method.setAccessible(true);
        method.invoke(listener, arg);
    }
}
