package fr.codinbox.echo.api.user;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.id.Identifiable;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.api.property.PropertyHolder;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.utils.Cleanable;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface User extends Identifiable<UUID>, PropertyHolder, Cleanable {

    @NotNull PropertyKey<String> PROPERTY_USERNAME = new PropertyKey<>("username");
    @NotNull PropertyKey<String> PROPERTY_CURRENT_PROXY_ID = new PropertyKey<>("current_proxy_id");
    @NotNull PropertyKey<String> PROPERTY_CURRENT_SERVER_ID = new PropertyKey<>("current_server_id");
    @NotNull PropertyKey<String> PROPERTY_PREVIOUS_SERVER_ID = new PropertyKey<>("previous_server_id");

    default @NotNull EchoFuture<@NotNull Optional<String>> getUsername() {
        return EchoFuture.of(this.getProperty(User.PROPERTY_USERNAME));
    }

    default @NotNull EchoFuture<@NotNull Optional<String>> getCurrentProxyId() {
        return EchoFuture.of(this.getProperty(User.PROPERTY_CURRENT_PROXY_ID));
    }

    default @NotNull EchoFuture<Void> setCurrentProxyId(final @NotNull String proxyId) {
        return this.setProperty(User.PROPERTY_CURRENT_PROXY_ID, proxyId);
    }

    default @NotNull EchoFuture<@NotNull Optional<Proxy>> getCurrentProxy() {
        return EchoFuture.of(this.getCurrentProxyId().thenCompose(
                pIdOpt -> pIdOpt.map(s -> Echo.getClient().getProxyById(s))
                .orElseGet(() -> EchoFuture.completed(Optional.empty()))
        ));
    }

    default @NotNull EchoFuture<@NotNull Optional<String>> getCurrentServerId() {
        return EchoFuture.of(this.getProperty(PROPERTY_CURRENT_SERVER_ID));
    }

    default @NotNull EchoFuture<Void> setCurrentServerId(final @NotNull String serverId) {
        return this.setProperty(PROPERTY_CURRENT_SERVER_ID, serverId);
    }

    default @NotNull EchoFuture<@NotNull Optional<Server>> getCurrentServer() {
        return EchoFuture.of(this.getCurrentServerId().thenCompose(
                serverIdOpt -> serverIdOpt.map(s -> Echo.getClient().getServerById(s))
                .orElseGet(() -> EchoFuture.completed(Optional.empty()))
        ));
    }

    default @NotNull EchoFuture<@NotNull Optional<String>> getPreviousServerId() {
        return EchoFuture.of(this.getProperty(PROPERTY_PREVIOUS_SERVER_ID));
    }

    default @NotNull EchoFuture<Void> setPreviousServerId(final @NotNull String serverId) {
        return this.setProperty(PROPERTY_PREVIOUS_SERVER_ID, serverId);
    }

    @NotNull EchoFuture<Void> tryConnectToProxy(final @NotNull Proxy proxy);

    @NotNull EchoFuture<ServerSwitchRequest.@NotNull PlayerResponse> tryConnectToServer(final @NotNull String id);

    @NotNull EchoFuture<ServerSwitchRequest.@NotNull PlayerResponse> tryConnectToServer(final @NotNull Server server);

}
