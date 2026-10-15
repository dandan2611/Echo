package fr.codinbox.echo.velocity.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.server.Server;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.DataPointSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class VelocityMetricsTest {

    private final @NotNull PrometheusRegistry registry = new PrometheusRegistry();
    private final @NotNull ProxyServer proxy = mock(ProxyServer.class);
    private final @NotNull EchoClient client = mock(EchoClient.class);
    private final @NotNull AtomicLong clock = new AtomicLong();
    private final @NotNull VelocityMetrics metrics =
            new VelocityMetrics(this.proxy, this.client, this.registry, "server_type", this.clock::get);

    @Test
    void exportsConnectedClients() {
        final Player lunar = player("lunarclient:v2", ProtocolVersion.MINECRAFT_1_21, 30);
        final Player vanilla = player("vanilla", ProtocolVersion.MINECRAFT_1_21, 90);
        final Player pending = player(null, ProtocolVersion.MINECRAFT_1_20_3, 60);
        when(this.proxy.getAllPlayers()).thenReturn(List.of(lunar, vanilla, pending));

        assertThat(this.gauge("minecraft_players_online")).isEqualTo(3.0);
        assertThat(this.gauge("velocity_players_by_brand", "brand", "lunarclient"))
                .isEqualTo(1.0);
        assertThat(this.gauge("velocity_players_by_brand", "brand", "unknown")).isEqualTo(1.0);
        assertThat(this.gauge(
                        "velocity_players_by_version",
                        "version",
                        ProtocolVersion.MINECRAFT_1_21.getMostRecentSupportedVersion()))
                .isEqualTo(2.0);
        assertThat(this.gauge("minecraft_player_ping_milliseconds", "stat", "max"))
                .isEqualTo(90.0);
    }

    @Test
    void countsLoginsAndSessionDurations() {
        final Player player = player("vanilla", ProtocolVersion.MINECRAFT_1_21, 10);
        final LoginEvent allowed = new LoginEvent(player);
        final LoginEvent denied = new LoginEvent(player);
        denied.setResult(ResultedEvent.ComponentResult.denied(Component.empty()));

        this.metrics.onLogin(allowed);
        this.metrics.onLogin(denied);
        this.metrics.onPostLogin(new PostLoginEvent(player));
        this.clock.set(600_000_000_000L);
        this.metrics.onDisconnect(new DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));

        assertThat(this.counter("velocity_logins", "result", "allowed")).isEqualTo(1.0);
        assertThat(this.counter("velocity_logins", "result", "denied")).isEqualTo(1.0);
        assertThat(this.counter("velocity_disconnects", "status", "successful_login"))
                .isEqualTo(1.0);
        final HistogramSnapshot.HistogramDataPointSnapshot sessions = (HistogramSnapshot.HistogramDataPointSnapshot)
                this.points("velocity_session_duration_seconds").getFirst();
        assertThat(sessions.getSum()).isEqualTo(600.0);
    }

    @Test
    void countsServerConnectionsByCachedGroup() {
        final Server server = mock(Server.class);
        doReturn(EchoFuture.completed(Optional.of("survival-games")))
                .when(server)
                .getProperty("server_type");
        when(this.client.getServerById("sg-1")).thenReturn(EchoFuture.completed(Optional.of(server)));
        final Player player = player("vanilla", ProtocolVersion.MINECRAFT_1_21, 10);
        final ServerConnection connection = mock(ServerConnection.class);
        when(connection.getServerInfo()).thenReturn(new ServerInfo("sg-1", new InetSocketAddress("127.0.0.1", 25565)));
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));

        this.metrics.onServerPostConnect(new ServerPostConnectEvent(player, null));
        this.metrics.onServerPostConnect(new ServerPostConnectEvent(player, null));

        assertThat(this.counter("velocity_server_connections", "group", "survival-games"))
                .isEqualTo(2.0);
        verify(this.client, times(1)).getServerById("sg-1");
    }

    @Test
    void unknownServerGroupIsNotCached() {
        when(this.client.getServerById("gone")).thenReturn(EchoFuture.completed(Optional.empty()));

        assertThat(this.metrics.group("gone").join()).isEqualTo("unknown");
        assertThat(this.metrics.group("gone").join()).isEqualTo("unknown");

        verify(this.client, times(2)).getServerById("gone");
    }

    private static @NotNull Player player(
            final @Nullable String brand, final @NotNull ProtocolVersion version, final long ping) {
        final Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getClientBrand()).thenReturn(brand);
        when(player.getProtocolVersion()).thenReturn(version);
        when(player.getPing()).thenReturn(ping);
        return player;
    }

    private double gauge(final @NotNull String name, final String @NotNull ... labels) {
        return ((GaugeSnapshot.GaugeDataPointSnapshot) this.point(name, labels)).getValue();
    }

    private double counter(final @NotNull String name, final String @NotNull ... labels) {
        return ((CounterSnapshot.CounterDataPointSnapshot) this.point(name, labels)).getValue();
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
