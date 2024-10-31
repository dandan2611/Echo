package fr.codinbox.echo.core.user;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.exception.resource.UnknownProxyException;
import fr.codinbox.echo.api.exception.user.UserHasNoProxyException;
import fr.codinbox.echo.api.messaging.impl.ProxySwitchRequest;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.core.property.AbstractPropertyHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class UserImpl extends AbstractPropertyHolder<UUID> implements User {

    public static final @NotNull String USERNAME_TO_ID_MAP = "users:username_to_id";
    public static final @NotNull String USER_MAP = "users:map";
    public static final @NotNull String USER_KEY_PREFIX = "user:%s";

    public UserImpl(final @NotNull UUID uuid) {
        super(uuid, USER_KEY_PREFIX.formatted(uuid));
    }

    @Override
    public @NotNull CompletableFuture<Void> tryConnectToProxyAsync(@NotNull Proxy proxy) {
        return proxy.sendMessage(new ProxySwitchRequest(proxy.getId(), super.getId()));
    }

    @Override
    public @NotNull CompletableFuture<ServerSwitchRequest.@NotNull PlayerResponse> tryConnectToServerAsync(final @NotNull String id) {
        final CompletableFuture<ServerSwitchRequest.@NotNull PlayerResponse> future = new CompletableFuture<>();
        this.getCurrentProxyIdAsync()
                .thenCompose(currentProxyIdOpt -> {
                    if (currentProxyIdOpt.isEmpty())
                        throw new UserHasNoProxyException(this.getId());

                    final String proxyId = currentProxyIdOpt.get();
                    return Echo.getClient().getProxyByIdAsync(proxyId);
                })
                .thenCompose(proxyOpt -> {
                    if (proxyOpt.isEmpty())
                        throw new UnknownProxyException();

                    final Proxy proxy = proxyOpt.get();
                    final ServerSwitchRequest message = new ServerSwitchRequest(id, this.getId());
                    message.onReply(r -> {
                        if (r instanceof ServerSwitchRequest.Response res) {
                            if (res.getResponses().containsKey(this.getId()))
                                future.complete(res.getResponses().get(this.getId()));
                            return true;
                        }
                        throw new IllegalStateException("Unexpected response type");
                    });
                    return proxy.sendMessage(message);
                });
        return future;
    }

    @Override
    public @NotNull CompletableFuture<ServerSwitchRequest.@NotNull PlayerResponse> tryConnectToServerAsync(final @NotNull Server server) {
        final CompletableFuture<ServerSwitchRequest.@NotNull PlayerResponse> future = new CompletableFuture<>();
        this.getCurrentProxyIdAsync()
                .thenCompose(currentProxyIdOpt -> {
                    if (currentProxyIdOpt.isEmpty())
                        throw new UserHasNoProxyException(this.getId());

                    final String proxyId = currentProxyIdOpt.get();
                    return Echo.getClient().getProxyByIdAsync(proxyId);
                })
                .thenCompose(proxyOpt -> {
                    if (proxyOpt.isEmpty())
                        throw new UnknownProxyException();

                    final Proxy proxy = proxyOpt.get();
                    final ServerSwitchRequest message = new ServerSwitchRequest(server.getId(), this.getId());
                    message.onReply(r -> {
                        if (r instanceof ServerSwitchRequest.Response res) {
                            if (res.getResponses().containsKey(this.getId()))
                                future.complete(res.getResponses().get(this.getId()));
                            return true;
                        }
                        throw new IllegalStateException("Unexpected response type");
                    });
                    return proxy.sendMessage(message);
                });
        return future;
    }

    @Override
    public @NotNull CompletableFuture<Void> cleanup() {
        return super.cleanup();
    }

}
