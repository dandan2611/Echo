package fr.codinbox.echo.core.user;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.exception.resource.UnknownProxyException;
import fr.codinbox.echo.api.exception.user.UserHasNoProxyException;
import fr.codinbox.echo.api.messaging.impl.ProxySwitchRequest;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.api.utils.NullableUtils;
import fr.codinbox.echo.core.property.AbstractPropertyHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class UserImpl extends AbstractPropertyHolder<UUID> implements User {

    public static final @NotNull String USERNAME_TO_ID_MAP = "users:username_to_id";
    public static final @NotNull String USER_MAP = "users:map";
    public static final @NotNull String USER_KEY = "user:%s";

    public UserImpl(final @NotNull UUID uuid) {
        super(uuid, USER_KEY.formatted(uuid));
    }

    @Override
    public @NotNull CompletableFuture<Void> connectToProxy(@NotNull String id) {
        return Echo.getClient().getProxyById(id)
                .thenCompose(p -> {
                    NullableUtils.requireNonNull(
                            p,
                            UnknownProxyException.class,
                            id
                    );
                    assert p != null;
                    return p.sendMessage(new ProxySwitchRequest(id, super.getId()));
                });
    }

    @Override
    public @NotNull CompletableFuture<Void> connectToProxy(@NotNull Proxy proxy) {
        return Echo.getClient().getProxyById(proxy.getId())
                .thenCompose(p -> {
                    NullableUtils.requireNonNull(
                            p,
                            UnknownProxyException.class,
                            proxy.getId()
                    );
                    assert p != null;
                    return p.sendMessage(new ProxySwitchRequest(proxy.getId(), super.getId()));
                });
    }

    @Override
    public @NotNull CompletableFuture<ServerSwitchRequest.@NotNull PlayerResponse> connectToServer(final @NotNull String id) {
        final CompletableFuture<ServerSwitchRequest.@NotNull PlayerResponse> future = new CompletableFuture<>();
        this.getCurrentProxyId()
                .thenCompose(currentProxyId -> {
                    NullableUtils.requireNonNull(
                            currentProxyId,
                            UserHasNoProxyException.class,
                            this.getId()
                    );
                    assert currentProxyId != null;
                    return Echo.getClient().getProxyById(currentProxyId);
                })
                .thenCompose(proxy -> {
                    NullableUtils.requireNonNull(
                            proxy,
                            UnknownProxyException.class,
                            this.getId()
                    );
                    assert proxy != null;
                    final ServerSwitchRequest message = new ServerSwitchRequest(id, this.getId());
                    message.onReply(r -> {
                        if (r instanceof ServerSwitchRequest.Response res) {
                            if (res.getResponses().containsKey(this.getId()))
                                future.complete(res.getResponses().get(this.getId()));
                        }
                        throw new IllegalStateException("Unexpected response type");
                    });
                    return proxy.sendMessage(message);
                });
        return future;
    }

    @Override
    public @NotNull CompletableFuture<ServerSwitchRequest.@NotNull PlayerResponse> connectToServer(@NotNull Server server) {
        return this.connectToServer(server.getId());
    }

    @Override
    public @NotNull CompletableFuture<Void> cleanup() {
        return super.cleanup();
    }

}
