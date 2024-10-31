package fr.codinbox.echo.api;

import fr.codinbox.echo.api.cache.CacheProvider;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.MessageTarget;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface EchoClient {

    @NotNull CompletableFuture<@NotNull Map<UUID, Instant>> getAllUsersAsync();

    @Blocking
    default @NotNull Map<UUID, Instant> getAllUsers() {
        return this.getAllUsersAsync().join();
    }

    @NotNull CompletableFuture<@NotNull Optional<User>> getUserByIdAsync(final @NotNull UUID id);

    @Blocking
    default @NotNull Optional<User> getUserById(final @NotNull UUID id) {
        return this.getUserByIdAsync(id).join();
    }

    @NotNull CompletableFuture<@NotNull Optional<User>> getUserByUsernameAsync(final @NotNull String username);

    @Blocking
    default @NotNull Optional<User> getUserByUsername(final @NotNull String username) {
        return this.getUserByUsernameAsync(username).join();
    }

    @NotNull CompletableFuture<Void> registerUserUsernameAsync(final @NotNull UUID id, final @NotNull String username);

    @Blocking
    default @NotNull Void registerUserUsername(final @NotNull UUID id, final @NotNull String username) {
        return this.registerUserUsernameAsync(id, username).join();
    }

    @NotNull CompletableFuture<Void> unregisterUserUsernameAsync(final @NotNull User user);

    @Blocking
    default @NotNull Void unregisterUserUsername(final @NotNull User user) {
        return this.unregisterUserUsernameAsync(user).join();
    }

    @NotNull CacheProvider getCacheProvider();

    @NotNull MessagingProvider getMessagingProvider();

    @NotNull MessageTarget.Builder newMessageTargetBuilder();

    @NotNull CompletableFuture<@NotNull Map<String, Instant>> getServersAsync();

    @Blocking
    default @NotNull Map<String, Instant> getServers() {
        return this.getServersAsync().join();
    }

    @NotNull CompletableFuture<@NotNull Optional<Server>> getServerByIdAsync(final @NotNull String id);

    @Blocking
    default @NotNull Optional<Server> getServerById(final @NotNull String id) {
        return this.getServerByIdAsync(id).join();
    }

    @NotNull CompletableFuture<@NotNull Map<String, Instant>> getProxiesAsync();

    @Blocking
    default @NotNull Map<String, Instant> getProxies() {
        return this.getProxiesAsync().join();
    }

    @NotNull CompletableFuture<@NotNull Optional<Proxy>> getProxyByIdAsync(final @NotNull String id);

    @Blocking
    default @NotNull Optional<Proxy> getProxyById(final @NotNull String id) {
        return this.getProxyByIdAsync(id).join();
    }

    @NotNull EchoResourceType getCurrentResourceType();

    @NotNull Optional<String> getCurrentResourceId();

    void shutdown();

    @NotNull String getLocalTopic();

    @NotNull CompletableFuture<@NotNull User> createUserAsync(final @NotNull UUID uuid,
                                                     final @NotNull String username,
                                                     final @NotNull String proxyId);

    @Blocking
    default @NotNull User createUser(final @NotNull UUID uuid,
                                     final @NotNull String username,
                                     final @NotNull String proxyId) {
        return this.createUserAsync(uuid, username, proxyId).join();
    }

    @NotNull CompletableFuture<Void> destroyUserAsync(final @NotNull User user);

    @Blocking
    default @NotNull Void destroyUser(final @NotNull User user) {
        return this.destroyUserAsync(user).join();
    }

    @NotNull CompletableFuture<Void> registerUserInServerAsync(final @NotNull User user, final @Nullable Server server);

    @Blocking
    default @NotNull Void registerUserInServer(final @NotNull User user, final @Nullable Server server) {
        return this.registerUserInServerAsync(user, server).join();
    }

}
