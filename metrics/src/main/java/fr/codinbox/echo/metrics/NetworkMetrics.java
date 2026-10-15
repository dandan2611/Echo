package fr.codinbox.echo.metrics;

import fr.codinbox.echo.api.server.ServerAvailability;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;

/** Exports a {@link NetworkSnapshot} as {@code echo_network_*} gauges. */
final class NetworkMetrics {

    private NetworkMetrics() {}

    static void export(final @NotNull NetworkSnapshot snapshot, final @NotNull MetricSnapshots.Builder snapshots) {
        final Gauges servers =
                new Gauges("echo_network_servers", "Servers registered in Echo, by group and availability");
        final Gauges serverPlayers = new Gauges("echo_network_server_players", "Players registered on each server");
        final Map<String, Integer> groupPlayers = new TreeMap<>();
        final Map<String, Map<ServerAvailability, Integer>> groupServers = new TreeMap<>();
        for (final NetworkSnapshot.ServerSample server : snapshot.servers()) {
            groupPlayers.merge(server.group(), server.players(), Integer::sum);
            groupServers
                    .computeIfAbsent(server.group(), ignored -> new TreeMap<>())
                    .merge(server.availability(), 1, Integer::sum);
            serverPlayers.add(server.players(), "server", server.id(), "group", server.group());
        }
        final Gauges players =
                new Gauges("echo_network_group_players", "Players registered on the servers of each group (game mode)");
        groupPlayers.forEach((group, count) -> players.add(count, "group", group));
        groupServers.forEach((group, byAvailability) -> {
            for (final ServerAvailability availability : ServerAvailability.values())
                servers.add(
                        byAvailability.getOrDefault(availability, 0),
                        "group",
                        group,
                        "availability",
                        availability.name().toLowerCase(java.util.Locale.ROOT));
        });
        final Gauges proxyPlayers = new Gauges("echo_network_proxy_players", "Players connected through each proxy");
        snapshot.proxies().forEach(proxy -> proxyPlayers.add(proxy.players(), "proxy", proxy.id()));

        snapshots.metricSnapshot(new Gauges("echo_network_players", "Players connected to the network")
                .add(snapshot.players())
                .build());
        snapshots.metricSnapshot(new Gauges("echo_network_proxies", "Proxies registered in Echo")
                .add(snapshot.proxies().size())
                .build());
        snapshots.metricSnapshot(players.build());
        snapshots.metricSnapshot(servers.build());
        snapshots.metricSnapshot(serverPlayers.build());
        snapshots.metricSnapshot(proxyPlayers.build());
    }
}
