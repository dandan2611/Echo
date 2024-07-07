package fr.codinbox.echo.api;

import fr.codinbox.echo.api.cache.CacheProvider;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.MessageTarget;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import org.jetbrains.annotations.CheckReturnValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RMapAsync;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface EchoClient {

    @NotNull CompletableFuture<@NotNull Map<String, Instant>> getConnectedUsers();

    @CheckReturnValue
    @NotNull CompletableFuture<@Nullable User> getUserById(final @NotNull UUID id);

    @CheckReturnValue
    @NotNull CompletableFuture<@Nullable User> getUserByUsername(final @NotNull String username);

    @NotNull CacheProvider getCacheProvider();

    @NotNull MessagingProvider getMessagingProvider();

    @NotNull MessageTarget.Builder newMessageTargetBuilder();

    @NotNull CompletableFuture<@NotNull Map<String, Instant>> getServers();

    @NotNull CompletableFuture<@Nullable Server> getServerById(final @NotNull String id);

    @NotNull CompletableFuture<@NotNull Map<String, Instant>> getProxies();

    @NotNull CompletableFuture<@Nullable Proxy> getProxyById(final @NotNull String id);

    @NotNull
    EchoResourceType getCurrentResourceType();

    @NotNull Optional<@NotNull String> getCurrentResourceId();

    void shutdown();

    @NotNull String getLocalTopic();

    @NotNull CompletableFuture<User> createUser(final @NotNull UUID uuid,
                                                final @NotNull String username,
                                                final @NotNull String proxyId);


    @NotNull CompletableFuture<Void> destroyUser(final @NotNull User user);

}
