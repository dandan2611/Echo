package fr.codinbox.echo.paper.listener;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerLoad;
import fr.codinbox.echo.api.server.ServerLoadManager;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.paper.EchoPaper;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class JoinListenerTest {

    private static final UUID PLAYER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private JoinListener listener;

    @Mock private EchoClient client;
    @Mock private Player player;
    @Mock private EchoPaper plugin;
    @Mock private org.bukkit.Server bukkitServer;
    @Mock private BukkitScheduler scheduler;
    @Mock private ServerLoadManager loadManager;
    @Mock private Logger logger;

    @BeforeEach
    void setUp() {
        this.listener = new JoinListener(plugin, loadManager);
        lenient().when(player.getUniqueId()).thenReturn(PLAYER_UUID);
        lenient().when(player.getName()).thenReturn("TestPlayer");
        lenient().when(plugin.getServer()).thenReturn(bukkitServer);
        lenient().when(plugin.getLogger()).thenReturn(logger);
        lenient().when(bukkitServer.getScheduler()).thenReturn(scheduler);
    }

    @Test
    void onJoinSchedulesRefreshBeforeReturningForMissingResourceId() throws Exception {
        when(client.getCurrentResourceId()).thenReturn(Optional.empty());
        when(loadManager.refresh()).thenReturn(EchoFuture.completed(snapshot()));
        try (MockedStatic<Echo> echo = mockStatic(Echo.class)) {
            echo.when(Echo::getClient).thenReturn(client);

            invokePrivate("onJoin", PlayerJoinEvent.class, new PlayerJoinEvent(player, "joined"));

            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            InOrder order = inOrder(scheduler, client);
            order.verify(scheduler).runTask(eq(plugin), task.capture());
            order.verify(client).getCurrentResourceId();
            verify(client, never()).getUserById(any());
            verify(loadManager, never()).refresh();

            task.getValue().run();
            verify(loadManager).refresh();
        }
    }

    @Test
    void onJoinCreatesNewUserAfterAsyncLookupCompletes() throws Exception {
        CapturedAsyncEchoFuture<Optional<User>> lookup = new CapturedAsyncEchoFuture<>();
        when(client.getCurrentResourceId()).thenReturn(Optional.of("lobby-1"));
        when(client.getUserById(PLAYER_UUID)).thenReturn(lookup);
        when(client.createUser(PLAYER_UUID, "TestPlayer", "lobby-1"))
                .thenReturn(EchoFuture.completed(mock(User.class)));
        try (MockedStatic<Echo> echo = mockStatic(Echo.class)) {
            echo.when(Echo::getClient).thenReturn(client);

            invokePrivate("onJoin", PlayerJoinEvent.class, new PlayerJoinEvent(player, "joined"));
            verify(client, never()).createUser(any(), any(), any());
            lookup.completeAndRun(Optional.empty());

            verify(client).createUser(PLAYER_UUID, "TestPlayer", "lobby-1");
        }
    }

    @Test
    void onJoinUpdatesAndRegistersExistingUser() throws Exception {
        CapturedAsyncEchoFuture<Optional<User>> lookup = new CapturedAsyncEchoFuture<>();
        User user = mock(User.class);
        Server server = mock(Server.class);
        when(client.getCurrentResourceId()).thenReturn(Optional.of("lobby-1"));
        when(client.getUserById(PLAYER_UUID)).thenReturn(lookup);
        when(user.getCurrentServerId()).thenReturn(EchoFuture.completed(Optional.of("lobby-old")));
        when(client.getServerById("lobby-1")).thenReturn(EchoFuture.completed(Optional.of(server)));
        try (MockedStatic<Echo> echo = mockStatic(Echo.class)) {
            echo.when(Echo::getClient).thenReturn(client);

            invokePrivate("onJoin", PlayerJoinEvent.class, new PlayerJoinEvent(player, "joined"));
            lookup.completeAndRun(Optional.of(user));

            verify(user).setPreviousServerId("lobby-old");
            verify(user, never()).setProperty(User.PROPERTY_CURRENT_SERVER_ID, "lobby-1");
            verify(client).registerUserInServer(user, server);
        }
    }

    @Test
    void onJoinDoesNotRegisterExistingUserWhenServerIsMissing() throws Exception {
        CapturedAsyncEchoFuture<Optional<User>> lookup = new CapturedAsyncEchoFuture<>();
        User user = mock(User.class);
        when(client.getCurrentResourceId()).thenReturn(Optional.of("lobby-1"));
        when(client.getUserById(PLAYER_UUID)).thenReturn(lookup);
        when(user.getCurrentServerId()).thenReturn(EchoFuture.completed(Optional.empty()));
        when(client.getServerById("lobby-1")).thenReturn(EchoFuture.completed(Optional.empty()));
        try (MockedStatic<Echo> echo = mockStatic(Echo.class)) {
            echo.when(Echo::getClient).thenReturn(client);

            invokePrivate("onJoin", PlayerJoinEvent.class, new PlayerJoinEvent(player, "joined"));
            lookup.completeAndRun(Optional.of(user));

            verify(user, never()).setProperty(User.PROPERTY_CURRENT_SERVER_ID, "lobby-1");
            verify(client, never()).registerUserInServer(any(), any());
        }
    }

    @Test
    void onQuitWithoutProxiesDestroysExistingUser() throws Exception {
        User user = mock(User.class);
        when(client.getProxies()).thenReturn(EchoFuture.completed(Map.of()));
        when(client.getUserById(PLAYER_UUID)).thenReturn(EchoFuture.completed(Optional.of(user)));
        when(client.destroyUser(user)).thenReturn(EchoFuture.completed(null));
        try (MockedStatic<Echo> echo = mockStatic(Echo.class)) {
            echo.when(Echo::getClient).thenReturn(client);

            invokePrivate("onQuit", PlayerQuitEvent.class, new PlayerQuitEvent(player, "left"));

            verify(client).destroyUser(user);
        }
    }

    @Test
    void onQuitWithoutProxiesIgnoresMissingUser() throws Exception {
        when(client.getProxies()).thenReturn(EchoFuture.completed(Map.of()));
        when(client.getUserById(PLAYER_UUID)).thenReturn(EchoFuture.completed(Optional.empty()));
        try (MockedStatic<Echo> echo = mockStatic(Echo.class)) {
            echo.when(Echo::getClient).thenReturn(client);

            invokePrivate("onQuit", PlayerQuitEvent.class, new PlayerQuitEvent(player, "left"));

            verify(client, never()).destroyUser(any());
        }
    }

    @Test
    void onQuitSchedulesRefreshBeforeReturningWhenProxyExists() throws Exception {
        when(client.getProxies()).thenReturn(EchoFuture.completed(Map.of("proxy-1", 1L)));
        when(loadManager.refresh()).thenReturn(EchoFuture.completed(snapshot()));
        try (MockedStatic<Echo> echo = mockStatic(Echo.class)) {
            echo.when(Echo::getClient).thenReturn(client);

            invokePrivate("onQuit", PlayerQuitEvent.class, new PlayerQuitEvent(player, "left"));

            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            InOrder order = inOrder(scheduler, client);
            order.verify(scheduler).runTask(eq(plugin), task.capture());
            order.verify(client).getProxies();
            verify(client, never()).getUserById(any());
            verify(loadManager, never()).refresh();

            task.getValue().run();
            verify(loadManager).refresh();
        }
    }

    @Test
    void failedLoadRefreshIsLoggedFromCapturedTask() throws Exception {
        IllegalStateException failure = new IllegalStateException("load provider offline");
        when(client.getCurrentResourceId()).thenReturn(Optional.empty());
        when(loadManager.refresh()).thenReturn(failedFuture(failure));
        try (MockedStatic<Echo> echo = mockStatic(Echo.class)) {
            echo.when(Echo::getClient).thenReturn(client);
            invokePrivate("onJoin", PlayerJoinEvent.class, new PlayerJoinEvent(player, "joined"));

            scheduledTask().run();

            verify(logger).log(Level.WARNING, "Failed to refresh Echo server load", failure);
        }
    }

    private Runnable scheduledTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTask(eq(plugin), task.capture());
        return task.getValue();
    }

    private void invokePrivate(String methodName, Class<?> paramType, Object arg) throws Exception {
        Method method = JoinListener.class.getDeclaredMethod(methodName, paramType);
        method.setAccessible(true);
        method.invoke(this.listener, arg);
    }

    private ServerLoadSnapshot snapshot() {
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        return new ServerLoadSnapshot(new ServerLoad(0, true), now, now.plusSeconds(30));
    }

    private static <T> EchoFuture<T> failedFuture(Throwable failure) {
        EchoFuture<T> future = new EchoFuture<>();
        future.completeExceptionally(failure);
        return future;
    }

    private static final class CapturedAsyncEchoFuture<T> extends EchoFuture<T> {
        private Runnable task;

        @Override
        public Executor defaultExecutor() {
            return task -> this.task = task;
        }

        private void completeAndRun(T value) {
            assertThat(this.complete(value)).isTrue();
            assertThat(this.task).isNotNull();
            this.task.run();
        }
    }
}
