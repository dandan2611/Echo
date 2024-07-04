package fr.codinbox.echo.core.messaging;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.messaging.MessageTarget;
import fr.codinbox.echo.core.proxy.ProxyImpl;
import fr.codinbox.echo.core.server.ServerImpl;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class MessageTargetBuilderImpl implements MessageTarget.Builder {

    private final @NotNull Set<String> targets;

    private boolean hasWildcards;

    public MessageTargetBuilderImpl() {
        this.targets = new HashSet<>();
        this.hasWildcards = false;
    }

    @Override
    public MessageTarget.@NotNull Builder withServer(@NotNull String serverId) {
        this.targets.add(ServerImpl.SERVER_TOPIC.formatted(serverId));
        return this;
    }

    @Override
    public MessageTarget.@NotNull Builder withServers(@NotNull String... serverIds) {
        for (String serverId : serverIds)
            this.withServer(serverId);
        return this;
    }

    @Override
    public MessageTarget.@NotNull Builder withServers(@NotNull Collection<String> serverIds) {
        for (String serverId : serverIds)
            this.withServer(serverId);
        return this;
    }

    @Override
    public @NotNull CompletableFuture<MessageTarget.@NotNull Builder> withAllServers() {
        return Echo.getClient().getServers().thenApply(servers -> this.withServers(servers.keySet()));
    }

    @Override
    public MessageTarget.@NotNull Builder withProxy(@NotNull String proxyId) {
        this.targets.add(ProxyImpl.PROXY_TOPIC.formatted(proxyId));
        return this;
    }

    @Override
    public MessageTarget.@NotNull Builder withProxies(@NotNull String... proxyIds) {
        for (String proxyId : proxyIds)
            this.withProxy(proxyId);
        return this;
    }

    @Override
    public MessageTarget.@NotNull Builder withProxies(@NotNull Collection<String> proxyIds) {
        for (String proxyId : proxyIds)
            this.withProxy(proxyId);
        return this;
    }

    @Override
    public @NotNull CompletableFuture<MessageTarget.@NotNull Builder> withAllProxies() {
        return Echo.getClient().getProxies().thenApply(proxies -> this.withProxies(proxies.keySet()));
    }

    @Override
    public @NotNull MessageTarget build() {
        return new MessageTarget(this.targets);
    }

}
