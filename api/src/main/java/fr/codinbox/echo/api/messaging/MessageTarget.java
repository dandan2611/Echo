package fr.codinbox.echo.api.messaging;

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
