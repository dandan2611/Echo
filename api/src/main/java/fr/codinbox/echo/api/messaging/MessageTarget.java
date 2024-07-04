package fr.codinbox.echo.api.messaging;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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

        @NotNull CompletableFuture<@NotNull Builder> withAllServers();

        @NotNull Builder withProxy(final @NotNull String proxyId);

        @NotNull Builder withProxies(final @NotNull String... proxyIds);

        @NotNull Builder withProxies(final @NotNull Collection<String> proxyIds);

        @NotNull CompletableFuture<@NotNull Builder> withAllProxies();

        default @NotNull CompletableFuture<@NotNull Builder> withEveryone() {
            return this.withAllServers().thenCompose(Builder::withAllProxies);
        }

        @NotNull MessageTarget build();

    }

}
