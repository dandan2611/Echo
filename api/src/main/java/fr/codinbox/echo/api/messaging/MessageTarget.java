package fr.codinbox.echo.api.messaging;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Set;

public final class MessageTarget {

    private final @NotNull Set<String> targets;

    public MessageTarget(@NotNull Set<String> targets) {
        this.targets = targets;
    }

    public @NotNull Set<String> getTargets() {
        return Set.copyOf(this.targets);
    }

    /**
     * Creates a target for a single server.
     */
    public static @NotNull MessageTarget server(final @NotNull String serverId) {
        return Echo.getClient().newMessageTargetBuilder().withServer(serverId).build();
    }

    /**
     * Creates a target for multiple servers.
     */
    public static @NotNull MessageTarget servers(final @NotNull String... serverIds) {
        return Echo.getClient().newMessageTargetBuilder().withServers(serverIds).build();
    }

    /**
     * Creates a target for a single proxy.
     */
    public static @NotNull MessageTarget proxy(final @NotNull String proxyId) {
        return Echo.getClient().newMessageTargetBuilder().withProxy(proxyId).build();
    }

    /**
     * Creates a target for multiple proxies.
     */
    public static @NotNull MessageTarget proxies(final @NotNull String... proxyIds) {
        return Echo.getClient().newMessageTargetBuilder().withProxies(proxyIds).build();
    }

    /**
     * Creates a target for all servers and proxies on the network.
     */
    public static @NotNull EchoFuture<@NotNull MessageTarget> everyone() {
        return EchoFuture.of(Echo.getClient().newMessageTargetBuilder()
                .withEveryone()
                .thenApply(Builder::build));
    }

    public interface Builder {

        @NotNull Builder withServer(final @NotNull String serverId);

        @NotNull Builder withServers(final @NotNull String... serverIds);

        @NotNull Builder withServers(final @NotNull Collection<String> serverIds);

        @NotNull EchoFuture<@NotNull Builder> withAllServers();

        @NotNull Builder withProxy(final @NotNull String proxyId);

        @NotNull Builder withProxies(final @NotNull String... proxyIds);

        @NotNull Builder withProxies(final @NotNull Collection<String> proxyIds);

        @NotNull EchoFuture<@NotNull Builder> withAllProxies();

        default @NotNull EchoFuture<@NotNull Builder> withEveryone() {
            return EchoFuture.of(this.withAllServers().thenCompose(Builder::withAllProxies));
        }

        @NotNull MessageTarget build();

    }

}
