package fr.codinbox.echo.paper;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.server.ServerAvailability;
import fr.codinbox.echo.api.server.ServerLoad;
import fr.codinbox.echo.api.server.ServerLoadManager;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import fr.codinbox.echo.paper.event.ServerDrainEvent;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
class EchoPaperTest {

    @Test
    void initialPropertiesIncludeTypedPlacementCapacity() {
        PropertyKey<String> serverType = PropertyKey.of("server_type", String.class);

        var properties = EchoPaper.initialProperties(Map.of(serverType, "lobby"), 500);

        assertThat(properties).containsEntry(serverType, "lobby")
                .containsEntry(ServerPlacement.PROPERTY_CAPACITY, 500);
        assertThat(properties.get(ServerPlacement.PROPERTY_CAPACITY)).isInstanceOf(Integer.class);
    }

    @Test
    void initialPropertiesRejectInvalidPlacementCapacity() {
        assertThatThrownBy(() -> EchoPaper.initialProperties(Map.of(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("placement capacity must be positive");
    }

    @Test
    void drainNotifiesGamePluginsOnTheMainThread() throws Exception {
        Fixture fixture = fixture(List.of(mock(Player.class)));
        Instant beforeDrain = fixture.clock.instant();

        var drained = fixture.plugin.beginDrain();

        verify(fixture.echoClient).setLocalServerAvailability(ServerAvailability.DRAINING);
        verify(fixture.pluginManager, never()).callEvent(any());
        assertThat(drained).isNotDone();

        ArgumentCaptor<Runnable> mainThread = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.scheduler).runTask(eq(fixture.plugin), mainThread.capture());
        mainThread.getValue().run();

        ArgumentCaptor<ServerDrainEvent> event = ArgumentCaptor.forClass(ServerDrainEvent.class);
        verify(fixture.pluginManager).callEvent(event.capture());
        assertThat(event.getValue().getDeadline()).isEqualTo(beforeDrain.plus(Duration.ofMinutes(30)));
        var order = inOrder(fixture.scheduler, fixture.pluginManager);
        order.verify(fixture.scheduler).runTask(eq(fixture.plugin), any(Runnable.class));
        order.verify(fixture.pluginManager).callEvent(any(ServerDrainEvent.class));
        order.verify(fixture.scheduler).runTaskLater(eq(fixture.plugin), any(Runnable.class), eq(30L * 60L * 20L));
        order.verify(fixture.scheduler).runTaskTimer(eq(fixture.plugin), any(Runnable.class), eq(0L), eq(20L));
        assertThat(drained).isCompleted();
    }

    @Test
    void mainThreadDelayCountsAgainstTheAnnouncedDeadline() throws Exception {
        Fixture fixture = fixture(List.of(mock(Player.class)));
        Instant expectedDeadline = fixture.clock.instant().plus(Duration.ofMinutes(30));

        fixture.plugin.beginDrain();
        fixture.clock.advance(Duration.ofMinutes(5));
        runMainThreadTask(fixture);

        ArgumentCaptor<ServerDrainEvent> event = ArgumentCaptor.forClass(ServerDrainEvent.class);
        verify(fixture.pluginManager).callEvent(event.capture());
        assertThat(event.getValue().getDeadline()).isEqualTo(expectedDeadline);
        verify(fixture.scheduler).runTaskLater(
                eq(fixture.plugin), any(Runnable.class), eq(25L * 60L * 20L));
    }

    @Test
    void slowDrainListenerDoesNotExtendTheAnnouncedDeadline() throws Exception {
        Fixture fixture = fixture(List.of(mock(Player.class)));
        doAnswer(ignored -> {
            fixture.clock.advance(Duration.ofMinutes(5));
            return null;
        }).when(fixture.pluginManager).callEvent(any(ServerDrainEvent.class));

        fixture.plugin.beginDrain();
        runMainThreadTask(fixture);

        verify(fixture.scheduler).runTaskLater(
                eq(fixture.plugin), any(Runnable.class), eq(25L * 60L * 20L));
    }

    @Test
    void drainedServerShutsDownAsSoonAsItIsEmpty() throws Exception {
        Player player = mock(Player.class);
        Fixture fixture = fixture(List.of(player), List.of());

        fixture.plugin.beginDrain();
        runMainThreadTask(fixture);

        ArgumentCaptor<Runnable> check = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.scheduler).runTaskTimer(eq(fixture.plugin), check.capture(), eq(0L), eq(20L));
        check.getValue().run();
        verify(fixture.server, never()).shutdown();
        check.getValue().run();
        verify(fixture.server).shutdown();
    }

    @Test
    void drainedServerShutsDownAtTheThirtyMinuteDeadline() throws Exception {
        Fixture fixture = fixture(List.of(mock(Player.class)));

        fixture.plugin.beginDrain();
        runMainThreadTask(fixture);

        ArgumentCaptor<Runnable> deadline = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.scheduler).runTaskLater(
                eq(fixture.plugin), deadline.capture(), eq(30L * 60L * 20L));
        deadline.getValue().run();

        verify(fixture.server).shutdown();
    }

    @Test
    void periodicCheckUsesTheAbsoluteDeadlineWhenTicksRunSlowly() throws Exception {
        Fixture fixture = fixture(List.of(mock(Player.class)));

        fixture.plugin.beginDrain();
        runMainThreadTask(fixture);
        fixture.clock.advance(Duration.ofMinutes(30));

        ArgumentCaptor<Runnable> check = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.scheduler).runTaskTimer(eq(fixture.plugin), check.capture(), eq(0L), eq(20L));
        check.getValue().run();

        verify(fixture.server).shutdown();
    }

    @Test
    void emptyAndDeadlineCallbacksShutDownOnlyOnce() throws Exception {
        Fixture fixture = fixture(List.of());

        fixture.plugin.beginDrain();
        runMainThreadTask(fixture);

        ArgumentCaptor<Runnable> check = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> deadline = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.scheduler).runTaskTimer(eq(fixture.plugin), check.capture(), eq(0L), eq(20L));
        verify(fixture.scheduler).runTaskLater(eq(fixture.plugin), deadline.capture(), any(Long.class));
        check.getValue().run();
        deadline.getValue().run();

        verify(fixture.server, times(1)).shutdown();
    }

    @Test
    void drainPersistenceFailureDoesNotNotifyOrScheduleShutdown() throws Exception {
        Fixture fixture = fixture(List.of(mock(Player.class)));
        when(fixture.echoClient.setLocalServerAvailability(ServerAvailability.DRAINING))
                .thenReturn(EchoFuture.of(CompletableFuture.failedFuture(
                        new IllegalStateException("Redis unavailable"))));

        var drained = fixture.plugin.beginDrain();

        assertThatThrownBy(drained::join).hasRootCauseMessage("Redis unavailable");
        verifyNoInteractions(fixture.scheduler, fixture.pluginManager);
    }

    @Test
    void drainEventFailureLeavesShutdownUnscheduledAndFailsTheAttempt() throws Exception {
        Fixture fixture = fixture(List.of(mock(Player.class)));
        doThrow(new IllegalStateException("listener failed"))
                .when(fixture.pluginManager).callEvent(any(ServerDrainEvent.class));

        var drained = fixture.plugin.beginDrain();
        runMainThreadTask(fixture);

        assertThatThrownBy(drained::join).hasRootCauseMessage("listener failed");
        verify(fixture.scheduler, never()).runTaskTimer(any(), any(Runnable.class), any(Long.class), any(Long.class));
        verify(fixture.scheduler, never()).runTaskLater(any(), any(Runnable.class), any(Long.class));
    }

    @Test
    void drainSchedulerFailureFailsTheAttempt() throws Exception {
        Fixture fixture = fixture(List.of(mock(Player.class)));
        BukkitTask deadlineTask = mock(BukkitTask.class);
        when(fixture.scheduler.runTaskLater(eq(fixture.plugin), any(Runnable.class), any(Long.class)))
                .thenReturn(deadlineTask);
        when(fixture.scheduler.runTaskTimer(eq(fixture.plugin), any(Runnable.class), eq(0L), eq(20L)))
                .thenThrow(new IllegalStateException("scheduler stopped"));

        var drained = fixture.plugin.beginDrain();
        runMainThreadTask(fixture);

        assertThatThrownBy(drained::join).hasRootCauseMessage("scheduler stopped");
        verify(deadlineTask).cancel();
    }

    @Test
    void activationIsRejectedWhileDraining() throws Exception {
        Fixture fixture = fixture(List.of());
        setField(fixture.plugin, "draining", new AtomicBoolean(true));

        assertThat(fixture.plugin.activate()).succeedsWithin(Duration.ofSeconds(1)).isEqualTo(false);
        verify(fixture.echoClient, never()).setLocalServerAvailability(ServerAvailability.ACTIVE);
    }

    @Test
    void activationThatRacesWithDrainRestoresDrainingAvailability() throws Exception {
        Fixture fixture = fixture(List.of());
        EchoFuture<Void> activation = new EchoFuture<>();
        when(fixture.echoClient.setLocalServerAvailability(ServerAvailability.ACTIVE)).thenReturn(activation);

        var activated = fixture.plugin.activate();
        setField(fixture.plugin, "draining", new AtomicBoolean(true));
        activation.complete(null);

        assertThat(activated).isCompletedWithValue(false);
        verify(fixture.echoClient).setLocalServerAvailability(ServerAvailability.DRAINING);
    }

    @Test
    void shutdownIsRequestedOnTheBukkitMainThread() throws Exception {
        Fixture fixture = fixture(List.of());

        assertThat(fixture.plugin.requestShutdown()).isTrue();
        verify(fixture.server, never()).shutdown();

        ArgumentCaptor<Runnable> mainThread = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.scheduler).runTask(eq(fixture.plugin), mainThread.capture());
        mainThread.getValue().run();
        verify(fixture.server).shutdown();
    }

    @Test
    void loadRefreshSamplesTheProviderOnTheBukkitMainThread() throws Exception {
        Fixture fixture = fixture(List.of());
        ServerLoadManager loadManager = mock(ServerLoadManager.class);
        ServerLoadSnapshot snapshot = new ServerLoadSnapshot(
                new ServerLoad(0, true), Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
        when(fixture.echoClient.getServerLoadManager()).thenReturn(loadManager);
        when(loadManager.refresh()).thenReturn(EchoFuture.completed(snapshot));

        var refreshed = fixture.plugin.refreshLoad();
        verify(loadManager, never()).refresh();

        runMainThreadTask(fixture);
        verify(loadManager).refresh();
        assertThat(refreshed).isCompletedWithValue(snapshot);
    }

    private static Fixture fixture(List<Player>... onlinePlayers) throws Exception {
        EchoPaper plugin = mock(EchoPaper.class, CALLS_REAL_METHODS);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        PluginManager pluginManager = mock(PluginManager.class);
        EchoClient echoClient = mock(EchoClient.class);
        doReturn(server).when(plugin).getServer();
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(echoClient.setLocalServerAvailability(ServerAvailability.DRAINING))
                .thenReturn(EchoFuture.completed(null));
        AtomicInteger playerRead = new AtomicInteger();
        doAnswer(ignored -> onlinePlayers[Math.min(
                        playerRead.getAndIncrement(), onlinePlayers.length - 1)])
                .when(server).getOnlinePlayers();
        setStopping(plugin);
        setField(plugin, "draining", new AtomicBoolean());
        setField(plugin, "echoClient", echoClient);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T12:00:00Z"));
        setField(plugin, "clock", clock);
        return new Fixture(plugin, server, scheduler, pluginManager, echoClient, clock);
    }

    private static void runMainThreadTask(Fixture fixture) {
        ArgumentCaptor<Runnable> mainThread = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.scheduler).runTask(eq(fixture.plugin), mainThread.capture());
        mainThread.getValue().run();
    }

    private static void setStopping(EchoPaper plugin) throws Exception {
        setField(plugin, "stopping", new AtomicBoolean());
    }

    private static void setField(EchoPaper plugin, String name, Object value) throws Exception {
        Field field = EchoPaper.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    private record Fixture(EchoPaper plugin, Server server, BukkitScheduler scheduler,
                           PluginManager pluginManager, EchoClient echoClient, MutableClock clock) {
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.instant;
        }
    }
}
