package fr.codinbox.echo.paper.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.codinbox.echo.metrics.Distribution;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.DataPointSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PaperMetricsTest {

    private final @NotNull PrometheusRegistry registry = new PrometheusRegistry();
    private final @NotNull Server server = mock(Server.class);
    private final @NotNull AtomicLong clock = new AtomicLong();
    private final @NotNull PaperMetrics metrics = new PaperMetrics(this.server, this.registry, this.clock::get);

    @Test
    void samplesTicksPlayersAndWorlds() {
        when(this.server.getTPS()).thenReturn(new double[] {19.5, 19.8, 20.0});
        when(this.server.getTickTimes()).thenReturn(new long[] {40_000_000L, 10_000_000L, 0L});
        doReturn(List.of(this.player(40), this.player(120))).when(this.server).getOnlinePlayers();
        when(this.server.getMaxPlayers()).thenReturn(120);
        final World world = world("world", 50, 400, 2);
        final World arena = world("arena", 10, 100, 0);
        final List<World> worlds = new ArrayList<>(List.of(world, arena));
        when(this.server.getWorlds()).thenReturn(worlds);

        this.metrics.sample();

        assertThat(gauge("minecraft_tps", "window", "1m")).isEqualTo(19.5);
        assertThat(gauge("minecraft_tick_duration_milliseconds", "stat", "max")).isEqualTo(40.0);
        assertThat(gauge("minecraft_tick_duration_milliseconds", "stat", "avg")).isEqualTo(25.0);
        assertThat(gauge("minecraft_players_online")).isEqualTo(2.0);
        assertThat(gauge("minecraft_players_max")).isEqualTo(120.0);
        assertThat(gauge("minecraft_player_ping_milliseconds", "stat", "avg")).isEqualTo(80.0);
        assertThat(gauge("minecraft_world_entities", "world", "world")).isEqualTo(50.0);
        assertThat(gauge("minecraft_world_loaded_chunks", "world", "arena")).isEqualTo(100.0);

        worlds.remove(arena);
        this.metrics.sample();

        assertThat(this.points("minecraft_world_entities"))
                .extracting(point -> point.getLabels().get("world"))
                .containsExactly("world");
    }

    @Test
    void countsJoinsQuitsAndSessionDurations() {
        final Player player = this.player(10);

        this.metrics.onJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
        this.clock.set(90_000_000_000L);
        this.metrics.onQuit(new PlayerQuitEvent(
                player, (net.kyori.adventure.text.Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertThat(this.counter("minecraft_player_joins")).isEqualTo(1.0);
        assertThat(this.counter("minecraft_player_quits")).isEqualTo(1.0);
        final HistogramSnapshot.HistogramDataPointSnapshot histogram = (HistogramSnapshot.HistogramDataPointSnapshot)
                this.points("minecraft_player_session_duration_seconds").getFirst();
        assertThat(histogram.getCount()).isEqualTo(1);
        assertThat(histogram.getSum()).isEqualTo(90.0);
    }

    @Test
    void tickDistributionIgnoresUnfilledSlots() {
        assertThat(PaperMetrics.tickDistribution(new long[] {0L, 0L})).isEqualTo(Distribution.EMPTY);
    }

    private @NotNull Player player(final int ping) {
        final Player player = mock(Player.class);
        when(player.getPing()).thenReturn(ping);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    private static @NotNull World world(
            final @NotNull String name, final int entities, final int chunks, final int players) {
        final World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getEntityCount()).thenReturn(entities);
        when(world.getChunkCount()).thenReturn(chunks);
        when(world.getPlayerCount()).thenReturn(players);
        return world;
    }

    private double gauge(final @NotNull String name, final String @NotNull ... labels) {
        return ((GaugeSnapshot.GaugeDataPointSnapshot) this.point(name, labels)).getValue();
    }

    private double counter(final @NotNull String name) {
        return ((CounterSnapshot.CounterDataPointSnapshot) this.point(name)).getValue();
    }

    private @NotNull DataPointSnapshot point(final @NotNull String name, final String @NotNull ... labels) {
        return this.points(name).stream()
                .filter(point -> point.getLabels().equals(Labels.of(labels)))
                .findFirst()
                .orElseThrow();
    }

    private @NotNull List<? extends DataPointSnapshot> points(final @NotNull String name) {
        for (final MetricSnapshot snapshot : this.registry.scrape())
            if (snapshot.getMetadata().getName().equals(name)) return snapshot.getDataPoints();
        throw new AssertionError("Missing metric " + name);
    }
}
