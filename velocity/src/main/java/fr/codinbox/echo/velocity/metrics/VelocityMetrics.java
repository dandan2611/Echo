package fr.codinbox.echo.velocity.metrics;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.metrics.ClientBrand;
import fr.codinbox.echo.metrics.Distribution;
import fr.codinbox.echo.metrics.Gauges;
import fr.codinbox.echo.metrics.NetworkSnapshot;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.NotNull;

/**
 * Velocity metrics: logins, sessions, transfers per server group and the connected clients (brand, version, ping).
 * Player gauges are computed at scrape time; Velocity's player list is thread-safe.
 */
public final class VelocityMetrics {

    /** Above this many cached server groups, the cache is cleared (game servers are short-lived). */
    static final int MAX_CACHED_GROUPS = 1024;

    private final @NotNull ProxyServer proxy;
    private final @NotNull EchoClient client;
    private final @NotNull String groupProperty;
    private final @NotNull LongSupplier nanoTime;
    private final @NotNull Counter logins;
    private final @NotNull Counter disconnects;
    private final @NotNull Counter serverConnections;
    private final @NotNull Histogram sessionDuration;
    private final @NotNull Map<UUID, Long> loggedInAt = new ConcurrentHashMap<>();
    private final @NotNull Map<String, String> serverGroups = new ConcurrentHashMap<>();

    public VelocityMetrics(
            final @NotNull ProxyServer proxy,
            final @NotNull EchoClient client,
            final @NotNull PrometheusRegistry registry,
            final @NotNull String groupProperty) {
        this(proxy, client, registry, groupProperty, System::nanoTime);
    }

    VelocityMetrics(
            final @NotNull ProxyServer proxy,
            final @NotNull EchoClient client,
            final @NotNull PrometheusRegistry registry,
            final @NotNull String groupProperty,
            final @NotNull LongSupplier nanoTime) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.client = Objects.requireNonNull(client, "client");
        this.groupProperty = Objects.requireNonNull(groupProperty, "groupProperty");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.logins = Counter.builder()
                .name("velocity_logins")
                .help("Login attempts by final result, after every plugin decided")
                .labelNames("result")
                .register(registry);
        this.disconnects = Counter.builder()
                .name("velocity_disconnects")
                .help("Disconnections by login status")
                .labelNames("status")
                .register(registry);
        this.serverConnections = Counter.builder()
                .name("velocity_server_connections")
                .help("Successful connections to a backend server, by server group (game mode)")
                .labelNames("group")
                .register(registry);
        this.sessionDuration = Histogram.builder()
                .name("velocity_session_duration_seconds")
                .help("Duration of network sessions, from login to disconnection")
                .classicUpperBounds(60, 300, 600, 900, 1800, 2700, 3600, 5400, 7200, 10800, 14400, 21600, 28800)
                .register(registry);
        registry.register(this::collectPlayers);
    }

    @Subscribe(order = PostOrder.LAST)
    public void onLogin(final @NotNull LoginEvent event) {
        this.logins
                .labelValues(event.getResult().isAllowed() ? "allowed" : "denied")
                .inc();
    }

    @Subscribe(order = PostOrder.LAST)
    public void onPostLogin(final @NotNull PostLoginEvent event) {
        this.loggedInAt.put(event.getPlayer().getUniqueId(), this.nanoTime.getAsLong());
    }

    @Subscribe(order = PostOrder.LAST)
    public void onDisconnect(final @NotNull DisconnectEvent event) {
        this.disconnects
                .labelValues(event.getLoginStatus().name().toLowerCase(Locale.ROOT))
                .inc();
        final Long started = this.loggedInAt.remove(event.getPlayer().getUniqueId());
        if (started != null) this.sessionDuration.observe((this.nanoTime.getAsLong() - started) / 1e9);
    }

    @Subscribe(order = PostOrder.LAST)
    public void onServerPostConnect(final @NotNull ServerPostConnectEvent event) {
        final Optional<ServerConnection> current = event.getPlayer().getCurrentServer();
        if (current.isEmpty()) return;
        this.group(current.get().getServerInfo().getName())
                .thenAccept(group -> this.serverConnections.labelValues(group).inc());
    }

    /** Resolves the group of an Echo server, cached because group properties never change. */
    @NotNull
    CompletableFuture<String> group(final @NotNull String serverId) {
        final String cached = this.serverGroups.get(serverId);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return this.client
                .getServerById(serverId)
                .thenCompose(server -> server.isEmpty()
                        ? CompletableFuture.completedFuture(Optional.<Object>empty())
                        : server.get().<Object>getProperty(this.groupProperty))
                .thenApply(value -> {
                    final Optional<String> group = value.map(Object::toString).filter(text -> !text.isBlank());
                    if (group.isEmpty()) return NetworkSnapshot.UNKNOWN_GROUP;
                    if (this.serverGroups.size() >= MAX_CACHED_GROUPS) this.serverGroups.clear();
                    this.serverGroups.put(serverId, group.get());
                    return group.get();
                })
                .exceptionally(error -> NetworkSnapshot.UNKNOWN_GROUP);
    }

    @NotNull
    MetricSnapshots collectPlayers() {
        final Collection<Player> players = this.proxy.getAllPlayers();
        final Map<String, Integer> brands = new TreeMap<>();
        final Map<String, Integer> versions = new TreeMap<>();
        final double[] pings = new double[players.size()];
        int index = 0;
        for (final Player player : players) {
            brands.merge(ClientBrand.normalize(player.getClientBrand()), 1, Integer::sum);
            versions.merge(player.getProtocolVersion().getMostRecentSupportedVersion(), 1, Integer::sum);
            if (index < pings.length) pings[index++] = player.getPing();
        }
        final Gauges brandGauge = new Gauges("velocity_players_by_brand", "Connected players by client brand");
        brands.forEach((brand, count) -> brandGauge.add(count, "brand", brand));
        final Gauges versionGauge = new Gauges("velocity_players_by_version", "Connected players by Minecraft version");
        versions.forEach((version, count) -> versionGauge.add(count, "version", version));
        return MetricSnapshots.of(
                new Gauges("minecraft_players_online", "Players connected to this proxy")
                        .add(players.size())
                        .build(),
                new Gauges("minecraft_player_ping_milliseconds", "Ping of the connected players (ms)")
                        .add(Distribution.of(index == pings.length ? pings : java.util.Arrays.copyOf(pings, index)))
                        .build(),
                brandGauge.build(),
                versionGauge.build());
    }
}
