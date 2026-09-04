package fr.codinbox.echo.core.user;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.exception.resource.UnknownProxyException;
import fr.codinbox.echo.api.exception.user.UserHasNoProxyException;
import fr.codinbox.echo.api.messaging.impl.ProxySwitchRequest;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.api.messaging.MessageTarget;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.core.property.AbstractPropertyHolder;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

public class UserImpl extends AbstractPropertyHolder<UUID> implements User {

    public static final @NotNull String USERNAME_TO_ID_MAP = "users:username_to_id";
    public static final @NotNull String USER_MAP = "users:map";
    public static final @NotNull String USER_KEY_PREFIX = "user:%s";

    private final @NotNull LongSupplier currentTimeMillis;

    public UserImpl(final @NotNull UUID uuid) {
        this(uuid, System::currentTimeMillis);
    }

    UserImpl(final @NotNull UUID uuid, final @NotNull LongSupplier currentTimeMillis) {
        super(uuid, USER_KEY_PREFIX.formatted(uuid));
        this.currentTimeMillis = currentTimeMillis;
    }

    @Override
    public @NotNull EchoFuture<Void> tryConnectToProxy(@NotNull Proxy proxy) {
        return EchoFuture.of(this.currentProxy().thenCompose(current ->
                current.sendMessage(new ProxySwitchRequest(proxy.getId(), super.getId()))));
    }

    @Override
    public @NotNull EchoFuture<ServerSwitchRequest.@NotNull PlayerResponse> tryConnectToServer(final @NotNull String id) {
        return this.tryConnectToServer(id, Duration.ofSeconds(10));
    }

    @Override
    public @NotNull EchoFuture<ServerSwitchRequest.@NotNull PlayerResponse> tryConnectToServer(
            final @NotNull String id, final @NotNull Duration timeout) {
        if (timeout.compareTo(Duration.ofMillis(1)) < 0)
            throw new IllegalArgumentException("timeout must be at least 1ms");

        final long deadline = Math.addExact(this.currentTimeMillis.getAsLong(), timeout.toMillis());
        final CompletableFuture<Proxy> currentProxy = this.currentProxy();
        final long proxyLookupTimeoutMillis = Math.max(0, deadline - this.currentTimeMillis.getAsLong());
        return EchoFuture.of(currentProxy.copy()
                .orTimeout(proxyLookupTimeoutMillis, TimeUnit.MILLISECONDS)
                .thenCompose(proxy -> {
            final long remainingMillis = deadline - this.currentTimeMillis.getAsLong();
            if (remainingMillis < 1)
                return CompletableFuture.failedFuture(new TimeoutException("Server switch deadline elapsed"));

            final ServerSwitchRequest request = new ServerSwitchRequest(id, this.getId());
            final var client = Echo.getClient();
            request.setReplyTopic(client.getLocalTopic());
            request.setTransferDeadlineEpochMillis(deadline);
            final MessageTarget target = MessageTarget.builder().withProxy(proxy.getId()).build();
            final String topic = target.getTargets().iterator().next();
            return client.getMessagingProvider().request(topic, request, ServerSwitchRequest.Response.class,
                    Duration.ofMillis(remainingMillis));
        }).thenApply(response -> {
            final ServerSwitchRequest.PlayerResponse playerResponse = response.getResponses().get(this.getId());
            if (playerResponse == null)
                throw new IllegalStateException("Proxy response omitted player " + this.getId());
            return playerResponse;
        }));
    }

    @Override
    public @NotNull EchoFuture<ServerSwitchRequest.@NotNull PlayerResponse> tryConnectToServer(final @NotNull Server server) {
        return this.tryConnectToServer(server.getId());
    }

    private java.util.concurrent.CompletableFuture<Proxy> currentProxy() {
        return this.getCurrentProxyId().thenCompose(currentProxyId -> {
            if (currentProxyId.isEmpty())
                throw new UserHasNoProxyException(this.getId());
            return Echo.getClient().getProxyById(currentProxyId.get());
        }).thenApply(proxy -> proxy.orElseThrow(UnknownProxyException::new)).toCompletableFuture();
    }

}
