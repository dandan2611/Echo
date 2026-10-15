package fr.codinbox.echo.paper.metrics;

import fr.codinbox.echo.metrics.Distribution;
import fr.codinbox.echo.metrics.DistributionGauge;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Paper metrics: tick health, worlds, players and per-server sessions. {@link #sample()} reads the Bukkit API and must
 * run on the main thread; the HTTP exporter only reads the stored values.
 */
public final class PaperMetrics implements Listener {

    /** Sampling period of {@link #sample()}, in ticks. */
    public static final long SAMPLE_PERIOD_TICKS = 100L;

    private final @NotNull Server server;
    private final @NotNull LongSupplier nanoTime;
    private final @NotNull Gauge tps;
    private final @NotNull DistributionGauge tickDuration;
    private final @NotNull Gauge playersOnline;
    private final @NotNull Gauge playersMax;
    private final @NotNull DistributionGauge ping;
    private final @NotNull Gauge worldEntities;
    private final @NotNull Gauge worldChunks;
    private final @NotNull Gauge worldPlayers;
    private final @NotNull Counter joins;
    private final @NotNull Counter quits;
    private final @NotNull Histogram sessionDuration;
    private final @NotNull Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();
    private final @NotNull Set<String> exportedWorlds = new HashSet<>();

    public PaperMetrics(final @NotNull Server server, final @NotNull PrometheusRegistry registry) {
        this(server, registry, System::nanoTime);
    }

    PaperMetrics(
            final @NotNull Server server,
            final @NotNull PrometheusRegistry registry,
            final @NotNull LongSupplier nanoTime) {
        this.server = Objects.requireNonNull(server, "server");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.tps = Gauge.builder()
                .name("minecraft_tps")
                .help("Ticks per second averaged over 1, 5 and 15 minutes")
                .labelNames("window")
                .register(registry);
        this.tickDuration = new DistributionGauge(
                registry, "minecraft_tick_duration_milliseconds", "Duration of the last 100 ticks", "ms");
        this.playersOnline = Gauge.builder()
                .name("minecraft_players_online")
                .help("Players connected to this server")
                .register(registry);
        this.playersMax = Gauge.builder()
                .name("minecraft_players_max")
                .help("Player slots of this server")
                .register(registry);
        this.ping = new DistributionGauge(
                registry, "minecraft_player_ping_milliseconds", "Ping of the connected players", "ms");
        this.worldEntities = Gauge.builder()
                .name("minecraft_world_entities")
                .help("Entities in each loaded world")
                .labelNames("world")
                .register(registry);
        this.worldChunks = Gauge.builder()
                .name("minecraft_world_loaded_chunks")
                .help("Loaded chunks in each world")
                .labelNames("world")
                .register(registry);
        this.worldPlayers = Gauge.builder()
                .name("minecraft_world_players")
                .help("Players in each world")
                .labelNames("world")
                .register(registry);
        this.joins = Counter.builder()
                .name("minecraft_player_joins")
                .help("Players who joined this server")
                .register(registry);
        this.quits = Counter.builder()
                .name("minecraft_player_quits")
                .help("Players who left this server")
                .register(registry);
        this.sessionDuration = Histogram.builder()
                .name("minecraft_player_session_duration_seconds")
                .help("Time spent on this server by each leaving player")
                .classicUpperBounds(30, 60, 120, 300, 600, 900, 1200, 1800, 2700, 3600, 5400, 7200, 14400)
                .register(registry);
    }

    /** Samples the Bukkit state. Main thread only. */
    public void sample() {
        final double[] averages = this.server.getTPS();
        if (averages.length >= 3) {
            this.tps.labelValues("1m").set(averages[0]);
            this.tps.labelValues("5m").set(averages[1]);
            this.tps.labelValues("15m").set(averages[2]);
        }
        this.tickDuration.set(tickDistribution(this.server.getTickTimes()));

        final Collection<? extends Player> players = this.server.getOnlinePlayers();
        this.playersOnline.set(players.size());
        this.playersMax.set(this.server.getMaxPlayers());
        final double[] pings = new double[players.size()];
        int index = 0;
        for (final Player player : players) pings[index++] = player.getPing();
        this.ping.set(Distribution.of(pings));

        final Set<String> worlds = new HashSet<>();
        for (final World world : this.server.getWorlds()) {
            final String name = world.getName();
            worlds.add(name);
            this.worldEntities.labelValues(name).set(world.getEntityCount());
            this.worldChunks.labelValues(name).set(world.getChunkCount());
            this.worldPlayers.labelValues(name).set(world.getPlayerCount());
        }
        for (final String unloaded : this.exportedWorlds) {
            if (worlds.contains(unloaded)) continue;
            this.worldEntities.remove(unloaded);
            this.worldChunks.remove(unloaded);
            this.worldPlayers.remove(unloaded);
        }
        this.exportedWorlds.clear();
        this.exportedWorlds.addAll(worlds);
    }

    static @NotNull Distribution tickDistribution(final long @NotNull [] tickNanos) {
        int count = 0;
        for (final long nanos : tickNanos) if (nanos > 0) count++;
        final double[] millis = new double[count];
        int index = 0;
        for (final long nanos : tickNanos) if (nanos > 0) millis[index++] = nanos / 1e6;
        return Distribution.of(millis);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final @NotNull PlayerJoinEvent event) {
        this.joins.inc();
        this.joinedAt.put(event.getPlayer().getUniqueId(), this.nanoTime.getAsLong());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final @NotNull PlayerQuitEvent event) {
        this.quits.inc();
        final Long joined = this.joinedAt.remove(event.getPlayer().getUniqueId());
        if (joined != null) this.sessionDuration.observe((this.nanoTime.getAsLong() - joined) / 1e9);
    }
}
