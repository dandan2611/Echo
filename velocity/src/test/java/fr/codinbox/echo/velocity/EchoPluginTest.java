package fr.codinbox.echo.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class EchoPluginTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private EchoPlugin plugin;
    private ProxyServer proxy;
    private Scheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        this.plugin = new EchoPlugin();
        this.proxy = mock(ProxyServer.class);
        this.scheduler = mock(Scheduler.class);
        when(this.proxy.getScheduler()).thenReturn(this.scheduler);
        setField(this.plugin, "proxy", this.proxy);
        setField(this.plugin, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shutdownRunsOnlyFromTheVelocityScheduler() {
        Scheduler.TaskBuilder builder = taskBuilder();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(this.scheduler.buildTask(eq(this.plugin), task.capture())).thenReturn(builder);

        assertThat(this.plugin.requestShutdown()).isTrue();
        verify(this.proxy, never()).shutdown();

        task.getValue().run();
        verify(this.proxy).shutdown();
    }

    @Test
    void drainDisablesActivationAndShutsDownWhenEmpty() {
        Scheduler.TaskBuilder deadlineBuilder = taskBuilder();
        Scheduler.TaskBuilder checkBuilder = taskBuilder();
        when(deadlineBuilder.delay(Duration.ofSeconds(30))).thenReturn(deadlineBuilder);
        when(checkBuilder.repeat(Duration.ofSeconds(1))).thenReturn(checkBuilder);
        when(this.scheduler.buildTask(eq(this.plugin), any(Runnable.class))).thenReturn(deadlineBuilder);
        ArgumentCaptor<Consumer<ScheduledTask>> check = consumerCaptor();
        when(this.scheduler.buildTask(eq(this.plugin), check.capture())).thenReturn(checkBuilder);
        when(this.proxy.getPlayerCount()).thenReturn(0);
        ScheduledTask scheduledCheck = mock(ScheduledTask.class);

        assertThat(this.plugin.beginDrain(NOW.plusSeconds(30))).isTrue();
        assertThat(this.plugin.activate()).isFalse();
        check.getValue().accept(scheduledCheck);

        verify(scheduledCheck).cancel();
        verify(this.proxy).shutdown();
    }

    @Test
    void lateAgonesDrainUsesTheEmptyOrDeadlineShutdownPath() {
        Scheduler.TaskBuilder deadlineBuilder = taskBuilder();
        Scheduler.TaskBuilder checkBuilder = taskBuilder();
        when(deadlineBuilder.delay(Duration.ofMinutes(30))).thenReturn(deadlineBuilder);
        when(checkBuilder.repeat(Duration.ofSeconds(1))).thenReturn(checkBuilder);
        when(this.scheduler.buildTask(eq(this.plugin), any(Runnable.class))).thenReturn(deadlineBuilder);
        ArgumentCaptor<Consumer<ScheduledTask>> check = consumerCaptor();
        when(this.scheduler.buildTask(eq(this.plugin), check.capture())).thenReturn(checkBuilder);
        when(this.proxy.getPlayerCount()).thenReturn(1, 0);
        ScheduledTask scheduledCheck = mock(ScheduledTask.class);

        assertThat(this.plugin.activate()).isTrue();
        assertThat(this.plugin.onAgonesDrain()).isCompleted();
        verify(deadlineBuilder).delay(Duration.ofMinutes(30));
        verify(this.proxy, never()).shutdown();

        check.getValue().accept(scheduledCheck);
        verify(this.proxy, never()).shutdown();
        check.getValue().accept(scheduledCheck);

        verify(scheduledCheck).cancel();
        verify(this.proxy).shutdown();
    }

    @Test
    void disconnectFindsTheExactPlayerAndRunsOnTheScheduler() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        Player player = mock(Player.class);
        when(this.proxy.getPlayer(userId)).thenReturn(Optional.of(player));
        Scheduler.TaskBuilder builder = taskBuilder();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(this.scheduler.buildTask(eq(this.plugin), task.capture())).thenReturn(builder);

        var disconnected = this.plugin.disconnectPlayer(userId, "maintenance");
        assertThat(disconnected).isNotDone();
        task.getValue().run();

        assertThat(disconnected).isCompletedWithValue(true);
        verify(player).disconnect(Component.text("maintenance"));
    }

    @Test
    void disconnectReportsAnAbsentPlayer() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        when(this.proxy.getPlayer(userId)).thenReturn(Optional.empty());
        Scheduler.TaskBuilder builder = taskBuilder();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(this.scheduler.buildTask(eq(this.plugin), task.capture())).thenReturn(builder);

        var disconnected = this.plugin.disconnectPlayer(userId, "maintenance");
        task.getValue().run();

        assertThat(disconnected).isCompletedWithValue(false);
    }

    @Test
    void disconnectRejectsAReplacedLocalSessionInsideTheScheduledTask() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000005");
        Player replacement = mock(Player.class);
        sessions().put(userId, "new-session");
        when(this.proxy.getPlayer(userId)).thenReturn(Optional.of(replacement));
        Scheduler.TaskBuilder builder = taskBuilder();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(this.scheduler.buildTask(eq(this.plugin), task.capture())).thenReturn(builder);

        var disconnected = this.plugin.disconnectPlayer(userId, "maintenance", "old-session");
        task.getValue().run();

        assertThat(disconnected).isCompletedWithValue(false);
        assertThat(sessions()).containsEntry(userId, "new-session");
        verify(replacement, never()).disconnect(any());
    }

    @Test
    void disconnectRemovesAndDisconnectsTheMatchingLocalSession() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000006");
        Player player = mock(Player.class);
        sessions().put(userId, "session-1");
        when(this.proxy.getPlayer(userId)).thenReturn(Optional.of(player));
        Scheduler.TaskBuilder builder = taskBuilder();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(this.scheduler.buildTask(eq(this.plugin), task.capture())).thenReturn(builder);

        var disconnected = this.plugin.disconnectPlayer(userId, "maintenance", "session-1");
        task.getValue().run();

        assertThat(disconnected).isCompletedWithValue(true);
        assertThat(sessions()).doesNotContainKey(userId);
        verify(player).disconnect(Component.text("maintenance"));
    }

    @Test
    void disconnectDoesNotRunAfterItsDeadline() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000007");
        sessions().put(userId, "session-1");
        Scheduler.TaskBuilder builder = taskBuilder();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(this.scheduler.buildTask(eq(this.plugin), task.capture())).thenReturn(builder);

        var disconnected = this.plugin.disconnectPlayer(
                userId, "maintenance", "session-1", NOW.plusSeconds(1).toEpochMilli());
        setField(this.plugin, "clock", Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        task.getValue().run();

        assertThat(disconnected).isCompletedExceptionally();
        assertThatThrownBy(disconnected::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
        assertThat(sessions()).containsEntry(userId, "session-1");
        verify(this.proxy, never()).getPlayer(userId);
    }

    private Scheduler.TaskBuilder taskBuilder() {
        Scheduler.TaskBuilder builder = mock(Scheduler.TaskBuilder.class);
        when(builder.schedule()).thenReturn(mock(ScheduledTask.class));
        return builder;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Consumer<ScheduledTask>> consumerCaptor() {
        return ArgumentCaptor.forClass(Consumer.class);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = EchoPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<UUID, String> sessions() throws Exception {
        Field field = EchoPlugin.class.getDeclaredField("userSessions");
        field.setAccessible(true);
        return (ConcurrentMap<UUID, String>) field.get(this.plugin);
    }
}
