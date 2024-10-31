package fr.codinbox.echo.api.user;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.id.Identifiable;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.api.property.PropertyHolder;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.utils.Cleanable;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface User extends Identifiable<UUID>, PropertyHolder, Cleanable {

    @NotNull PropertyKey<String> PROPERTY_USERNAME = new PropertyKey<>("username");
    @NotNull PropertyKey<String> PROPERTY_CURRENT_PROXY_ID = new PropertyKey<>("current_proxy_id");
    @NotNull PropertyKey<String> PROPERTY_CURRENT_SERVER_ID = new PropertyKey<>("current_server_id");
    @NotNull PropertyKey<String> PROPERTY_PREVIOUS_SERVER_ID = new PropertyKey<>("previous_server_id");

    default @NotNull CompletableFuture<@NotNull Optional<String>> getUsernameAsync() {
        return this.getPropertyAsync(User.PROPERTY_USERNAME);
    }

    @Blocking
    default @NotNull Optional<String> getUsername() {
        return this.getUsernameAsync().join();
    }

    default @NotNull CompletableFuture<@NotNull Optional<String>> getCurrentProxyIdAsync() {
        return this.getPropertyAsync(User.PROPERTY_CURRENT_PROXY_ID);
    }

    @Blocking
    default @NotNull Optional<String> getCurrentProxyId() {
        return this.getCurrentProxyIdAsync().join();
    }

    default @NotNull CompletableFuture<Void> setCurrentProxyIdAsync(final @NotNull String proxyId) {
        return this.setPropertyAsync(User.PROPERTY_CURRENT_PROXY_ID, proxyId);
    }

    @Blocking
    default void setCurrentProxyId(final @NotNull String proxyId) {
        this.setCurrentProxyIdAsync(proxyId).join();
    }

    default @NotNull CompletableFuture<@NotNull Optional<Proxy>> getCurrentProxyAsync() {
        return this.getCurrentProxyIdAsync().thenCompose(
                pIdOpt -> pIdOpt.map(s -> Echo.getClient().getProxyByIdAsync(s))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()))
        );
    }

    default @NotNull CompletableFuture<@NotNull Optional<String>> getCurrentServerIdAsync() {
        return this.getPropertyAsync(PROPERTY_CURRENT_SERVER_ID);
    }

    @Blocking
    default @NotNull Optional<String> getCurrentServerId() {
        return this.getCurrentServerIdAsync().join();
    }

    default @NotNull CompletableFuture<Void> setCurrentServerIdAsync(final @NotNull String serverId) {
        return this.setPropertyAsync(PROPERTY_CURRENT_SERVER_ID, serverId);
    }

    @Blocking
    default void setCurrentServerId(final @NotNull String serverId) {
        this.setCurrentServerIdAsync(serverId).join();
    }

    default @NotNull CompletableFuture<@NotNull Optional<Server>> getCurrentServerAsync() {
        return this.getCurrentServerIdAsync().thenCompose(
                serverIdOpt -> serverIdOpt.map(s -> Echo.getClient().getServerByIdAsync(s))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()))
        );
    }

    @Blocking
    default @NotNull Optional<Server> getCurrentServer() {
        return this.getCurrentServerAsync().join();
    }

    default @NotNull CompletableFuture<@NotNull Optional<String>> getPreviousServerIdAsync() {
        return this.getPropertyAsync(PROPERTY_PREVIOUS_SERVER_ID);
    }

    @Blocking
    default @NotNull Optional<String> getPreviousServerId() {
        return this.getPreviousServerIdAsync().join();
    }

    default @NotNull CompletableFuture<Void> setPreviousServerIdAsync(final @NotNull String serverId) {
        return this.setPropertyAsync(PROPERTY_PREVIOUS_SERVER_ID, serverId);
    }

    @Blocking
    default void setPreviousServerId(final @NotNull String serverId) {
        this.setPreviousServerIdAsync(serverId).join();
    }

    @NotNull CompletableFuture<Void> tryConnectToProxyAsync(final @NotNull Proxy proxy);

    @Blocking
    default void tryConnectToProxy(final @NotNull Proxy proxy) {
        this.tryConnectToProxyAsync(proxy).join();
    }

    @NotNull CompletableFuture<ServerSwitchRequest.@NotNull PlayerResponse> tryConnectToServerAsync(final @NotNull String id);

    @Blocking
    default @NotNull ServerSwitchRequest.@NotNull PlayerResponse tryConnectToServer(final @NotNull String id) {
        return this.tryConnectToServerAsync(id).join();
    }

    @NotNull CompletableFuture<ServerSwitchRequest.@NotNull PlayerResponse> tryConnectToServerAsync(final @NotNull Server server);

    @Blocking
    default @NotNull ServerSwitchRequest.@NotNull PlayerResponse tryConnectToServer(final @NotNull Server server) {
        return this.tryConnectToServerAsync(server).join();
    }

}
