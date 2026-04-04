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

/**
 * Represents a player connected to the Echo network.
 *
 * <p>A user is identified by their Minecraft {@link UUID} and tracked across the network
 * with properties for their username, current proxy, current server, and previous server.</p>
 *
 * <p>Users also support arbitrary custom properties via the {@link PropertyHolder} interface,
 * and can be transferred between servers and proxies.</p>
 *
 * <pre>{@code
 * // Get a user and read their info
 * User user = client.getUserById(uuid).await().orElseThrow();
 * String name = user.getUsername().await().orElse("unknown");
 * Optional<Server> server = user.getCurrentServer().await();
 *
 * // Transfer to another server
 * ServerSwitchRequest.PlayerResponse resp = user.tryConnectToServer("survival-1").await();
 * if (resp.isSuccessful()) {
 *     System.out.println("Player transferred!");
 * }
 * }</pre>
 *
 * @see PropertyHolder
 * @see Identifiable
 */
public interface User extends Identifiable<UUID>, PropertyHolder, Cleanable {

    /**
     * Property key for the user's username.
     */
    @NotNull PropertyKey<String> PROPERTY_USERNAME = new PropertyKey<>("username");

    /**
     * Property key for the identifier of the proxy the user is currently connected to.
     */
    @NotNull PropertyKey<String> PROPERTY_CURRENT_PROXY_ID = new PropertyKey<>("current_proxy_id");

    /**
     * Property key for the identifier of the server the user is currently on.
     */
    @NotNull PropertyKey<String> PROPERTY_CURRENT_SERVER_ID = new PropertyKey<>("current_server_id");

    /**
     * Property key for the identifier of the server the user was previously on.
     */
    @NotNull PropertyKey<String> PROPERTY_PREVIOUS_SERVER_ID = new PropertyKey<>("previous_server_id");

    /**
     * Gets the user's username.
     *
     * <pre>{@code
     * String name = user.getUsername().await().orElse("unknown");
     * }</pre>
     *
     * @return a future that completes with the username, or empty if not set
     */
    default @NotNull EchoFuture<@NotNull Optional<String>> getUsername() {
        return EchoFuture.of(this.getProperty(User.PROPERTY_USERNAME));
    }

    /**
     * Gets the identifier of the proxy this user is currently connected to.
     *
     * <pre>{@code
     * Optional<String> proxyId = user.getCurrentProxyId().await();
     * }</pre>
     *
     * @return a future that completes with the proxy ID, or empty if not connected
     */
    default @NotNull EchoFuture<@NotNull Optional<String>> getCurrentProxyId() {
        return EchoFuture.of(this.getProperty(User.PROPERTY_CURRENT_PROXY_ID));
    }

    /**
     * Sets the identifier of the proxy this user is connected to.
     *
     * @param proxyId the proxy identifier
     * @return a future that completes when the property is updated
     */
    default @NotNull EchoFuture<Void> setCurrentProxyId(final @NotNull String proxyId) {
        return this.setProperty(User.PROPERTY_CURRENT_PROXY_ID, proxyId);
    }

    /**
     * Gets the {@link Proxy} this user is currently connected to.
     *
     * <p>This resolves the proxy ID to a full {@link Proxy} object. Returns empty
     * if the user has no proxy ID set or if the proxy no longer exists.</p>
     *
     * <pre>{@code
     * Optional<Proxy> proxy = user.getCurrentProxy().await();
     * proxy.ifPresent(p -> System.out.println("Connected via: " + p.getId()));
     * }</pre>
     *
     * @return a future that completes with the proxy, or empty if not connected
     */
    default @NotNull EchoFuture<@NotNull Optional<Proxy>> getCurrentProxy() {
        return EchoFuture.of(this.getCurrentProxyId().thenCompose(
                pIdOpt -> pIdOpt.map(s -> Echo.getClient().getProxyById(s))
                .orElseGet(() -> EchoFuture.completed(Optional.empty()))
        ));
    }

    /**
     * Gets the identifier of the server this user is currently on.
     *
     * <pre>{@code
     * Optional<String> serverId = user.getCurrentServerId().await();
     * }</pre>
     *
     * @return a future that completes with the server ID, or empty if not on a server
     */
    default @NotNull EchoFuture<@NotNull Optional<String>> getCurrentServerId() {
        return EchoFuture.of(this.getProperty(PROPERTY_CURRENT_SERVER_ID));
    }

    /**
     * Sets the identifier of the server this user is currently on.
     *
     * @param serverId the server identifier
     * @return a future that completes when the property is updated
     */
    default @NotNull EchoFuture<Void> setCurrentServerId(final @NotNull String serverId) {
        return this.setProperty(PROPERTY_CURRENT_SERVER_ID, serverId);
    }

    /**
     * Gets the {@link Server} this user is currently on.
     *
     * <p>This resolves the server ID to a full {@link Server} object. Returns empty
     * if the user has no server ID set or if the server no longer exists.</p>
     *
     * <pre>{@code
     * Optional<Server> server = user.getCurrentServer().await();
     * server.ifPresent(s -> System.out.println("Playing on: " + s.getId()));
     * }</pre>
     *
     * @return a future that completes with the server, or empty if not on a server
     */
    default @NotNull EchoFuture<@NotNull Optional<Server>> getCurrentServer() {
        return EchoFuture.of(this.getCurrentServerId().thenCompose(
                serverIdOpt -> serverIdOpt.map(s -> Echo.getClient().getServerById(s))
                .orElseGet(() -> EchoFuture.completed(Optional.empty()))
        ));
    }

    /**
     * Gets the identifier of the server the user was previously on.
     *
     * <p>This is updated automatically when a player switches servers.</p>
     *
     * <pre>{@code
     * Optional<String> previousId = user.getPreviousServerId().await();
     * }</pre>
     *
     * @return a future that completes with the previous server ID, or empty if none
     */
    default @NotNull EchoFuture<@NotNull Optional<String>> getPreviousServerId() {
        return EchoFuture.of(this.getProperty(PROPERTY_PREVIOUS_SERVER_ID));
    }

    /**
     * Sets the identifier of the user's previous server.
     *
     * @param serverId the previous server identifier
     * @return a future that completes when the property is updated
     */
    default @NotNull EchoFuture<Void> setPreviousServerId(final @NotNull String serverId) {
        return this.setProperty(PROPERTY_PREVIOUS_SERVER_ID, serverId);
    }

    /**
     * Requests to transfer this user to a different proxy.
     *
     * <p>This sends a {@link fr.codinbox.echo.api.messaging.impl.ProxySwitchRequest}
     * to the user's current proxy, which will handle the connection transfer.</p>
     *
     * <pre>{@code
     * Proxy targetProxy = client.getProxyById("proxy-us").await().orElseThrow();
     * user.tryConnectToProxy(targetProxy).await();
     * }</pre>
     *
     * @param proxy the target proxy to transfer the user to
     * @return a future that completes when the transfer request has been sent
     */
    @NotNull EchoFuture<Void> tryConnectToProxy(final @NotNull Proxy proxy);

    /**
     * Requests to transfer this user to a different server by its identifier.
     *
     * <p>This sends a {@link ServerSwitchRequest} to the user's current proxy,
     * which performs the actual server switch. The response indicates whether the
     * transfer was successful.</p>
     *
     * <pre>{@code
     * ServerSwitchRequest.PlayerResponse resp = user.tryConnectToServer("survival-1").await();
     * if (resp.isSuccessful()) {
     *     System.out.println("Transfer successful!");
     * } else {
     *     System.out.println("Transfer failed: " + resp.getStatus());
     * }
     * }</pre>
     *
     * @param id the target server identifier
     * @return a future that completes with the transfer response
     * @see ServerSwitchRequest.PlayerResponse
     * @see ServerSwitchRequest.ServerSwitchRequestStatus
     */
    @NotNull EchoFuture<ServerSwitchRequest.@NotNull PlayerResponse> tryConnectToServer(final @NotNull String id);

    /**
     * Requests to transfer this user to a different server.
     *
     * <p>Convenience overload that accepts a {@link Server} object instead of a string ID.</p>
     *
     * <pre>{@code
     * Server target = client.getServerById("survival-1").await().orElseThrow();
     * ServerSwitchRequest.PlayerResponse resp = user.tryConnectToServer(target).await();
     * }</pre>
     *
     * @param server the target server
     * @return a future that completes with the transfer response
     * @see #tryConnectToServer(String)
     */
    @NotNull EchoFuture<ServerSwitchRequest.@NotNull PlayerResponse> tryConnectToServer(final @NotNull Server server);

}
