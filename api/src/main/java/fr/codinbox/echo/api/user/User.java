package fr.codinbox.echo.api.user;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.id.Identifiable;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.api.property.PropertyHolder;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.utils.Cleanable;
import org.jetbrains.annotations.CheckReturnValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface User extends Identifiable<UUID>, PropertyHolder, Cleanable {

    @NotNull String PROPERTY_USERNAME = "username";
    @NotNull String PROPERTY_CURRENT_PROXY_ID = "current_proxy_id";
    @NotNull String PROPERTY_CURRENT_SERVER_ID = "current_server_id";
    @NotNull String PROPERTY_PREVIOUS_SERVER_ID = "previous_server_id";

    @CheckReturnValue
    default @NotNull CompletableFuture<@Nullable String> getUsername() {
        return this.getProperty(User.PROPERTY_USERNAME);
    }

    @CheckReturnValue
    default @NotNull CompletableFuture<@Nullable String> getCurrentProxyId() {
        return this.getProperty(User.PROPERTY_CURRENT_PROXY_ID);
    }

    default @NotNull CompletableFuture<Void> setCurrentProxyId(final @NotNull String proxyId) {
        return this.setProperty(User.PROPERTY_CURRENT_PROXY_ID, proxyId);
    }

    @CheckReturnValue
    default @NotNull CompletableFuture<@Nullable Proxy> getCurrentProxy() {
        return this.getCurrentProxyId().thenCompose(proxyId -> {
            if (proxyId == null)
                return CompletableFuture.completedFuture(null);
            return Echo.getClient().getProxyById(proxyId);
        });
    }

    default @NotNull CompletableFuture<Void> setCurrentProxy(final @NotNull Proxy proxy) {
        return this.setCurrentProxyId(proxy.getId());
    }

    @CheckReturnValue
    default @NotNull CompletableFuture<@Nullable String> getCurrentServerId() {
        return this.getProperty(PROPERTY_CURRENT_SERVER_ID);
    }

    default @NotNull CompletableFuture<Void> setCurrentServerId(final @NotNull String serverId) {
        return this.setProperty(PROPERTY_CURRENT_SERVER_ID, serverId);
    }

    @CheckReturnValue
    default @NotNull CompletableFuture<@Nullable Server> getCurrentServer() {
        return this.getCurrentServerId().thenCompose(serverId -> {
                    if (serverId == null)
                        return CompletableFuture.completedFuture(null);
                    return Echo.getClient().getServerById(serverId);
                });
    }

    @CheckReturnValue
    default @NotNull CompletableFuture<@Nullable String> getPreviousServerId() {
        return this.getProperty(PROPERTY_PREVIOUS_SERVER_ID);
    }

    default @NotNull CompletableFuture<Void> setPreviousServerId(final @NotNull String serverId) {
        return this.setProperty(PROPERTY_PREVIOUS_SERVER_ID, serverId);
    }

    @NotNull CompletableFuture<Void> connectToProxy(final @NotNull String id);

    default @NotNull CompletableFuture<Void> connectToProxy(final @NotNull Proxy proxy) {
        return this.connectToProxy(proxy.getId());
    }

    @NotNull CompletableFuture<ServerSwitchRequest.@NotNull PlayerResponse> connectToServer(final @NotNull String id);

    default @NotNull CompletableFuture<ServerSwitchRequest.@NotNull PlayerResponse> connectToServer(final @NotNull Server server) {
        return this.connectToServer(server.getId());
    }

}
