package fr.codinbox.echo.metrics;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerAvailability;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;

/**
 * Network-wide state read from Echo: every user, server and proxy registered in Redis.
 *
 * @param players users connected to the network
 * @param servers registered servers
 * @param proxies registered proxies
 */
public record NetworkSnapshot(
        int players,
        @NotNull List<ServerSample> servers,
        @NotNull List<ProxySample> proxies) {

    /** Label value used for servers without the group property. */
    public static final @NotNull String UNKNOWN_GROUP = "unknown";

    public NetworkSnapshot {
        servers = List.copyOf(Objects.requireNonNull(servers, "servers"));
        proxies = List.copyOf(Objects.requireNonNull(proxies, "proxies"));
    }

    /**
     * One registered server.
     *
     * @param id the Echo resource ID
     * @param group the value of the group property, or {@link #UNKNOWN_GROUP}
     * @param availability whether the server accepts new players
     * @param players users registered on the server
     */
    public record ServerSample(
            @NotNull String id,
            @NotNull String group,
            @NotNull ServerAvailability availability,
            int players) {

        public ServerSample {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(availability, "availability");
        }
    }

    /**
     * One registered proxy.
     *
     * @param id the Echo resource ID
     * @param players users connected through the proxy
     */
    public record ProxySample(@NotNull String id, int players) {

        public ProxySample {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * Reads the network state. Resources removed during the scan are skipped; any other failure fails the future.
     *
     * @param client the Echo client
     * @param groupProperty the server property used as the group
     * @return the snapshot
     */
    public static @NotNull CompletableFuture<NetworkSnapshot> read(
            final @NotNull EchoClient client, final @NotNull String groupProperty) {
        final CompletableFuture<Integer> players = client.getAllUsers().thenApply(Map::size);
        final CompletableFuture<List<ServerSample>> servers = client.getServers()
                .thenCompose(ids -> all(ids.keySet().stream()
                        .map(id -> readServer(client, id, groupProperty))
                        .toList()));
        final CompletableFuture<List<ProxySample>> proxies = client.getProxies()
                .thenCompose(ids -> all(
                        ids.keySet().stream().map(id -> readProxy(client, id)).toList()));
        return players.thenCombine(servers, (count, serverSamples) -> Map.entry(count, serverSamples))
                .thenCombine(
                        proxies,
                        (partial, proxySamples) ->
                                new NetworkSnapshot(partial.getKey(), partial.getValue(), proxySamples));
    }

    private static @NotNull CompletableFuture<Optional<ServerSample>> readServer(
            final @NotNull EchoClient client, final @NotNull String id, final @NotNull String groupProperty) {
        return client.getServerById(id).thenCompose(found -> {
            if (found.isEmpty()) return CompletableFuture.completedFuture(Optional.<ServerSample>empty());
            final Server server = found.get();
            final CompletableFuture<Optional<Object>> group = server.getProperty(groupProperty);
            final CompletableFuture<ServerAvailability> availability = server.getAvailability();
            final CompletableFuture<Integer> users = server.getConnectedUsers().thenApply(Map::size);
            return CompletableFuture.allOf(group, availability, users)
                    .thenApply(ignored -> Optional.of(new ServerSample(
                            id,
                            group.join()
                                    .map(Object::toString)
                                    .filter(value -> !value.isBlank())
                                    .orElse(UNKNOWN_GROUP),
                            availability.join(),
                            users.join())));
        });
    }

    private static @NotNull CompletableFuture<Optional<ProxySample>> readProxy(
            final @NotNull EchoClient client, final @NotNull String id) {
        return client.getProxyById(id)
                .thenCompose(found -> found.isEmpty()
                        ? CompletableFuture.completedFuture(Optional.<ProxySample>empty())
                        : found.get()
                                .getConnectedUsers()
                                .thenApply(users -> Optional.of(new ProxySample(id, users.size()))));
    }

    private static <T> @NotNull CompletableFuture<List<T>> all(
            final @NotNull List<CompletableFuture<Optional<T>>> futures) {
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream()
                        .map(CompletableFuture::join)
                        .flatMap(Optional::stream)
                        .toList());
    }
}
