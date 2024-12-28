package fr.codinbox.echo.api;

import fr.codinbox.echo.api.cache.RedisCacheProvider;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.MessageTarget;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Echo Client.
 */
public interface EchoClient {

    /**
     * Gets all connected users of the entire network.
     *
     * @return a future that completes with a map of all users and their join time
     */
    @NotNull CompletableFuture<@NotNull Map<UUID, Long>> getAllUsersAsync();

    /**
     * Gets all connected users of the entire network.
     *
     * @return a map of all users and their join time
     */
    @Blocking
    default @NotNull Map<UUID, Long> getAllUsers() {
        return this.getAllUsersAsync().join();
    }

    /**
     * Gets a user by its identifier.
     *
     * @param id the user identifier
     * @return a future that completes with an optional user
     */
    @NotNull CompletableFuture<@NotNull Optional<User>> getUserByIdAsync(final @NotNull UUID id);

    /**
     * Gets a user by its identifier.
     *
     * @param id the user identifier
     * @return an optional containing the user if found
     */
    @Blocking
    default @NotNull Optional<User> getUserById(final @NotNull UUID id) {
        return this.getUserByIdAsync(id).join();
    }

    /**
     * Gets a user by its username.
     *
     * @param username the username
     * @return a future that completes with an optional user
     */
    @NotNull CompletableFuture<@NotNull Optional<User>> getUserByUsernameAsync(final @NotNull String username);

    /**
     * Gets a user by its username.
     *
     * @param username the username
     * @return an optional containing the user if found
     */
    @Blocking
    default @NotNull Optional<User> getUserByUsername(final @NotNull String username) {
        return this.getUserByUsernameAsync(username).join();
    }

    /**
     * Registers a user's username. The previous username of the user is unregistered (if set).
     *
     * @param id the user identifier
     * @param username the username
     * @return a future that completes when the username is registered
     */
    @NotNull CompletableFuture<Void> registerUserUsernameAsync(final @NotNull UUID id, final @NotNull String username);

    /**
     * Registers a user's username. The previous username of the user is unregistered (if set).
     *
     * @param id the user identifier
     * @param username the username
     * @return a future that completes when the username is registered
     */
    @Blocking
    default @NotNull Void registerUserUsername(final @NotNull UUID id, final @NotNull String username) {
        return this.registerUserUsernameAsync(id, username).join();
    }

    /**
     * Unregisters a user's username.
     *
     * @param user the user
     * @return a future that completes when the username is unregistered
     */
    @NotNull CompletableFuture<Void> unregisterUserUsernameAsync(final @NotNull User user);

    /**
     * Unregisters a user's username.
     *
     * @param user the user
     * @return a future that completes when the username is unregistered
     */
    @Blocking
    default @NotNull Void unregisterUserUsername(final @NotNull User user) {
        return this.unregisterUserUsernameAsync(user).join();
    }

    /**
     * Gets the cache provider.
     * Going over there leads to a lot of low-level Redis operations and could be dangerous for the state of the network. Proceed with caution.
     *
     * @return the cache provider
     */
    @NotNull RedisCacheProvider getCacheProvider();

    /**
     * Gets the messaging provider.
     *
     * @return the messaging provider
     */
    @NotNull MessagingProvider getMessagingProvider();

    /**
     * Creates a new message target builder. Useful for sending {@link fr.codinbox.echo.api.messaging.EchoMessage} to targets.
     *
     * @return a new message target builder
     */
    @NotNull MessageTarget.Builder newMessageTargetBuilder();

    /**
     * Gets all servers of the network.
     *
     * @return a future that completes with a map of all servers and their creation time
     */
    @NotNull CompletableFuture<@NotNull Map<String, Long>> getServersAsync();

    @Blocking
    default @NotNull Map<String, Long> getServers() {
        return this.getServersAsync().join();
    }

    /**
     * Gets a server by its identifier.
     *
     * @param id the server identifier
     * @return a future that completes with an optional server
     */
    @NotNull CompletableFuture<@NotNull Optional<Server>> getServerByIdAsync(final @NotNull String id);

    /**
     * Gets a server by its identifier.
     *
     * @param id the server identifier
     * @return an optional containing the server if found
     */
    @Blocking
    default @NotNull Optional<Server> getServerById(final @NotNull String id) {
        return this.getServerByIdAsync(id).join();
    }

    /**
     * Gets all proxies of the network.
     *
     * @return a future that completes with a map of all proxies and their creation time
     */
    @NotNull CompletableFuture<@NotNull Map<String, Long>> getProxiesAsync();

    /**
     * Gets all proxies of the network.
     *
     * @return a map of all proxies and their creation time
     */
    @Blocking
    default @NotNull Map<String, Long> getProxies() {
        return this.getProxiesAsync().join();
    }

    /**
     * Gets a proxy by its identifier.
     *
     * @param id the proxy identifier
     * @return a future that completes with an optional proxy
     */
    @NotNull CompletableFuture<@NotNull Optional<Proxy>> getProxyByIdAsync(final @NotNull String id);

    /**
     * Gets a proxy by its identifier.
     *
     * @param id the proxy identifier
     * @return an optional containing the proxy if found
     */
    @Blocking
    default @NotNull Optional<Proxy> getProxyById(final @NotNull String id) {
        return this.getProxyByIdAsync(id).join();
    }

    /**
     * Gets the current (local) resource type.
     *
     * @return the current (local) resource type
     */
    @NotNull EchoResourceType getCurrentResourceType();

    /**
     * Gets the current (local) resource identifier.
     *
     * @return the current (local) resource identifier
     */
    @NotNull Optional<String> getCurrentResourceId();

    /**
     * Shuts down the client.
     */
    void shutdown();

    /**
     * Gets the local topic.
     *
     * @return the local topic
     */
    @NotNull String getLocalTopic();

    /**
     * Creates a new user and registers it in the network.
     * This method should not be used manually, except for those who know what they are doing.
     *
     * @param uuid the user identifier
     * @param username the username
     * @param proxyId the proxy identifier
     * @return
     */
    @NotNull CompletableFuture<@NotNull User> createUserAsync(final @NotNull UUID uuid,
                                                     final @NotNull String username,
                                                     final @NotNull String proxyId);

    /**
     * Creates a new user and registers it in the network.
     * This method should not be used manually, except for those who know what they are doing.
     *
     * @param uuid the user identifier
     * @param username the username
     * @param proxyId the proxy identifier
     * @return the created user
     */
    @Blocking
    default @NotNull User createUser(final @NotNull UUID uuid,
                                     final @NotNull String username,
                                     final @NotNull String proxyId) {
        return this.createUserAsync(uuid, username, proxyId).join();
    }

    /**
     * Destroys a user.
     * This method should not be used manually, except for those who know what they are doing.
     *
     * @param user the user to destroy
     * @return a future that completes when the user is destroyed
     */
    @NotNull CompletableFuture<Void> destroyUserAsync(final @NotNull User user);

    /**
     * Destroys a user.
     * This method should not be used manually, except for those who know what they are doing.
     *
     * @param user the user to destroy
     */
    @Blocking
    default void destroyUser(final @NotNull User user) {
        this.destroyUserAsync(user).join();
    }

    /**
     * Registers a user in a server.
     *
     * @param user the user
     * @param server the server
     * @return a future that completes when the user is registered in the server
     */
    @NotNull CompletableFuture<Void> registerUserInServerAsync(final @NotNull User user, final @Nullable Server server);

    /**
     * Registers a user in a server.
     *
     * @param user the user
     * @param server the server
     */
    @Blocking
    default void registerUserInServer(final @NotNull User user, final @Nullable Server server) {
        this.registerUserInServerAsync(user, server).join();
    }

}
