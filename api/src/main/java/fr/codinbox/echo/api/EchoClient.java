package fr.codinbox.echo.api;

import fr.codinbox.echo.api.cache.CacheProvider;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.MessageTarget;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The main client interface for interacting with the Echo network.
 *
 * <p>Provides methods to query and manage users, servers, and proxies across the entire network,
 * as well as access to the messaging and caching subsystems.</p>
 *
 * <p>Obtain an instance via {@link Echo#getClient()}:</p>
 * <pre>{@code
 * EchoClient client = Echo.getClient();
 *
 * // Query a user
 * Optional<User> user = client.getUserById(uuid).await();
 *
 * // List all servers
 * Map<String, Long> servers = client.getServers().await();
 * }</pre>
 *
 * <p>All methods returning {@link EchoFuture} can be used asynchronously (via {@code thenAccept},
 * {@code thenCompose}, etc.) or blocking (via {@link EchoFuture#await()}).</p>
 *
 * @see Echo#getClient()
 * @see EchoFuture
 */
public interface EchoClient {

    /**
     * Gets all connected users across the entire network.
     *
     * <p>Returns a map where keys are player UUIDs and values are their join timestamps
     * (milliseconds since epoch).</p>
     *
     * <pre>{@code
     * Map<UUID, Long> users = client.getAllUsers().await();
     * System.out.println("Online players: " + users.size());
     * }</pre>
     *
     * @return a future that completes with a map of user UUIDs to join timestamps
     */
    @NotNull EchoFuture<@NotNull Map<UUID, Long>> getAllUsers();

    /**
     * Gets a user by their UUID.
     *
     * <pre>{@code
     * Optional<User> user = client.getUserById(playerUuid).await();
     * user.ifPresent(u -> {
     *     String name = u.getUsername().await().orElse("unknown");
     *     System.out.println("Found player: " + name);
     * });
     * }</pre>
     *
     * @param id the user's UUID (typically their Minecraft UUID)
     * @return a future that completes with the user, or empty if not found
     */
    @NotNull EchoFuture<@NotNull Optional<User>> getUserById(final @NotNull UUID id);

    /**
     * Gets a user by their username.
     *
     * <pre>{@code
     * Optional<User> user = client.getUserByUsername("Steve").await();
     * }</pre>
     *
     * @param username the player's username (case-sensitive)
     * @return a future that completes with the user, or empty if not found
     */
    @NotNull EchoFuture<@NotNull Optional<User>> getUserByUsername(final @NotNull String username);

    /**
     * Registers or updates a user's username mapping.
     *
     * <p>If the user already had a username registered, the old mapping is removed first.
     * This is typically called automatically by the platform plugin when a player joins.</p>
     *
     * @param id       the user's UUID
     * @param username the username to register
     * @return a future that completes when the username is registered
     */
    @NotNull EchoFuture<Void> registerUserUsername(final @NotNull UUID id, final @NotNull String username);

    /**
     * Unregisters a user's username mapping.
     *
     * <p>After this call, {@link #getUserByUsername(String)} will no longer find this user
     * by their previous username.</p>
     *
     * @param user the user whose username mapping should be removed
     * @return a future that completes when the username is unregistered
     */
    @NotNull EchoFuture<Void> unregisterUserUsername(final @NotNull User user);

    /**
     * Gets the cache provider.
     *
     * <p><b>Warning:</b> This exposes low-level cache operations. Incorrect usage can corrupt
     * the network state. Use the higher-level APIs (properties, messaging) when possible.</p>
     *
     * <pre>{@code
     * CacheProvider cache = client.getCacheProvider();
     * cache.setObject("my:custom:key", "value").join();
     * String value = cache.<String>getObject("my:custom:key").join();
     * }</pre>
     *
     * @return the cache provider
     * @see CacheProvider
     */
    @NotNull CacheProvider getCacheProvider();

    /**
     * Gets the messaging provider for pub/sub operations.
     *
     * <p>Use this to subscribe to topics and publish messages across the network.</p>
     *
     * <pre>{@code
     * MessagingProvider messaging = client.getMessagingProvider();
     *
     * // Subscribe to a custom topic
     * messaging.subscribe("my-alerts", AlertMessage.class, alert -> {
     *     System.out.println("Alert: " + alert.getText());
     * });
     * }</pre>
     *
     * @return the messaging provider
     * @see MessagingProvider
     */
    @NotNull MessagingProvider getMessagingProvider();

    /**
     * Creates a new {@link MessageTarget.Builder} for constructing message targets.
     *
     * <p>The builder allows you to compose complex targets (multiple servers, proxies, or both)
     * before sending a message.</p>
     *
     * <pre>{@code
     * MessageTarget target = client.newMessageTargetBuilder()
     *     .withServer("lobby-1")
     *     .withServer("lobby-2")
     *     .withProxy("proxy-eu")
     *     .build();
     *
     * new AlertMessage("Hello!").sendTo(target);
     * }</pre>
     *
     * @return a new message target builder
     * @see MessageTarget
     */
    @NotNull MessageTarget.Builder newMessageTargetBuilder();

    /**
     * Gets all registered servers on the network.
     *
     * <p>Returns a map where keys are server identifiers and values are their registration
     * timestamps (milliseconds since epoch).</p>
     *
     * <pre>{@code
     * Map<String, Long> servers = client.getServers().await();
     * servers.forEach((id, createdAt) ->
     *     System.out.println("Server: " + id)
     * );
     * }</pre>
     *
     * @return a future that completes with a map of server IDs to creation timestamps
     */
    @NotNull EchoFuture<@NotNull Map<String, Long>> getServers();

    /**
     * Gets a server by its identifier.
     *
     * <pre>{@code
     * Optional<Server> server = client.getServerById("lobby-1").await();
     * server.ifPresent(s -> {
     *     Address addr = s.getAddress();
     *     System.out.println("Server at " + addr.getHost() + ":" + addr.getPort());
     * });
     * }</pre>
     *
     * @param id the server identifier (e.g. {@code "lobby-1"}, {@code "survival-2"})
     * @return a future that completes with the server, or empty if not found
     */
    @NotNull EchoFuture<@NotNull Optional<Server>> getServerById(final @NotNull String id);

    /**
     * Gets all registered proxies on the network.
     *
     * <p>Returns a map where keys are proxy identifiers and values are their registration
     * timestamps (milliseconds since epoch).</p>
     *
     * <pre>{@code
     * Map<String, Long> proxies = client.getProxies().await();
     * System.out.println("Active proxies: " + proxies.size());
     * }</pre>
     *
     * @return a future that completes with a map of proxy IDs to creation timestamps
     */
    @NotNull EchoFuture<@NotNull Map<String, Long>> getProxies();

    /**
     * Gets a proxy by its identifier.
     *
     * <pre>{@code
     * Optional<Proxy> proxy = client.getProxyById("proxy-eu").await();
     * }</pre>
     *
     * @param id the proxy identifier (e.g. {@code "proxy-eu"}, {@code "proxy-us"})
     * @return a future that completes with the proxy, or empty if not found
     */
    @NotNull EchoFuture<@NotNull Optional<Proxy>> getProxyById(final @NotNull String id);

    /**
     * Gets the resource type of the current (local) node.
     *
     * <p>Returns whether this node is running as a {@link EchoResourceType#SERVER}
     * or a {@link EchoResourceType#PROXY}.</p>
     *
     * @return the current node's resource type
     */
    @NotNull EchoResourceType getCurrentResourceType();

    /**
     * Gets the unique identifier of the current (local) node.
     *
     * <p>This corresponds to the {@code ECHO_RESOURCE_ID} environment variable.</p>
     *
     * @return the current node's identifier, or empty if not configured
     */
    @NotNull Optional<String> getCurrentResourceId();

    /**
     * Shuts down the Echo client, releasing all resources and connections.
     *
     * <p>This is called automatically by the platform plugin on server shutdown.
     * After calling this method, the client is no longer usable.</p>
     */
    void shutdown();

    /**
     * Gets the messaging topic for the current (local) node.
     *
     * <p>This is the topic that other nodes use to send messages directly to this node.
     * It is derived from the node's resource type and identifier.</p>
     *
     * @return the local messaging topic
     */
    @NotNull String getLocalTopic();

    /**
     * Creates a new user and registers it in the network.
     *
     * <p><b>Internal use only.</b> This is called automatically by the platform plugin when
     * a player connects to the proxy. Manual usage may corrupt the network state.</p>
     *
     * @param uuid     the player's UUID
     * @param username the player's username
     * @param proxyId  the identifier of the proxy the player connected through
     * @return a future that completes with the newly created user
     */
    @NotNull EchoFuture<@NotNull User> createUser(final @NotNull UUID uuid,
                                                   final @NotNull String username,
                                                   final @NotNull String proxyId);

    /**
     * Destroys a user, removing all their data from the network.
     *
     * <p><b>Internal use only.</b> This is called automatically by the platform plugin when
     * a player disconnects from the proxy. Manual usage may corrupt the network state.</p>
     *
     * @param user the user to destroy
     * @return a future that completes when the user is fully cleaned up
     */
    @NotNull EchoFuture<Void> destroyUser(final @NotNull User user);

    /**
     * Registers a user in a server, updating their current server tracking.
     *
     * <p>This records the user as connected to the specified server. Pass {@code null}
     * to unregister the user from their current server.</p>
     *
     * @param user   the user to register
     * @param server the server to register the user in, or {@code null} to unregister
     * @return a future that completes when the registration is updated
     */
    @NotNull EchoFuture<Void> registerUserInServer(final @NotNull User user, final @Nullable Server server);

}
