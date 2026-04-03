package fr.codinbox.echo.paper.listener;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Map;
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
    private EchoClient mockClient;
    @Mock
    private Player mockPlayer;

    private final UUID playerUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new JoinListener();
        lenient().when(mockPlayer.getUniqueId()).thenReturn(playerUuid);
        lenient().when(mockPlayer.getName()).thenReturn("TestPlayer");
    }

    @Test
    void onJoin_noResourceId_returnsEarly() throws Exception {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCurrentResourceId()).thenReturn(Optional.empty());

            PlayerJoinEvent event = new PlayerJoinEvent(mockPlayer, "joined");
            invokePrivate("onJoin", PlayerJoinEvent.class, event);

            verify(mockClient, never()).getUserById(any());
        }
    }

    @Test
    void onJoin_newUser_createsUser() throws Exception {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCurrentResourceId()).thenReturn(Optional.of("lobby-1"));
            when(mockClient.getUserById(playerUuid))
                    .thenReturn(EchoFuture.completed(Optional.empty()));
            when(mockClient.createUser(eq(playerUuid), eq("TestPlayer"), eq("lobby-1")))
                    .thenReturn(EchoFuture.completed(mock(User.class)));

            PlayerJoinEvent event = new PlayerJoinEvent(mockPlayer, "joined");
            invokePrivate("onJoin", PlayerJoinEvent.class, event);

            // The async callback calls createUser - we need to wait briefly
            Thread.sleep(100);
            verify(mockClient).createUser(playerUuid, "TestPlayer", "lobby-1");
        }
    }

    @Test
    void onQuit_noProxies_destroysUser() throws Exception {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            User mockUser = mock(User.class);
            when(mockClient.getProxies()).thenReturn(EchoFuture.completed(Map.of()));
            when(mockClient.getUserById(playerUuid))
                    .thenReturn(EchoFuture.completed(Optional.of(mockUser)));
            when(mockClient.destroyUser(mockUser)).thenReturn(EchoFuture.completed(null));

            PlayerQuitEvent event = new PlayerQuitEvent(mockPlayer, "left");
            invokePrivate("onQuit", PlayerQuitEvent.class, event);

            Thread.sleep(100);
            verify(mockClient).destroyUser(mockUser);
        }
    }

    @Test
    void onQuit_proxiesExist_doesNotDestroy() throws Exception {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getProxies()).thenReturn(EchoFuture.completed(Map.of("proxy-1", 1L)));

            PlayerQuitEvent event = new PlayerQuitEvent(mockPlayer, "left");
            invokePrivate("onQuit", PlayerQuitEvent.class, event);

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
