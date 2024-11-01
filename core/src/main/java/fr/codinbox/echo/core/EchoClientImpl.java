package fr.codinbox.echo.core;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.cache.CacheProvider;
import fr.codinbox.echo.api.exception.UnknownResourceException;
import fr.codinbox.echo.api.exception.resource.UnknownServerException;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessageTarget;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.messaging.impl.ServerStatusNotification;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.api.utils.Cleanable;
import fr.codinbox.echo.api.utils.EnvUtils;
import fr.codinbox.echo.core.cache.RedisCacheProvider;
import fr.codinbox.echo.core.messaging.MessageTargetBuilderImpl;
import fr.codinbox.echo.core.messaging.provider.RedisMessagingProvider;
import fr.codinbox.echo.core.property.AbstractPropertyHolder;
import fr.codinbox.echo.core.proxy.ProxyImpl;
import fr.codinbox.echo.core.server.ServerImpl;
import fr.codinbox.echo.core.user.UserImpl;
import fr.codinbox.echo.core.utils.MapUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RMapAsync;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class EchoClientImpl implements EchoClient {

    private static final @NotNull String LOGGER_NAME = "echo";

    private final @NotNull Logger logger;

    private final @NotNull CacheProvider cacheProvider;
    private final @NotNull MessagingProvider messagingProvider;
    private final @NotNull EchoResourceType resourceType;
    private final @NotNull String resourceId;
    private final @NotNull String topic;

    public EchoClientImpl(final @NotNull RedisConnection connection,
                          final @NotNull EchoResourceType resourceType,
                          final @NotNull String resourceId) {
        this.logger = Logger.getLogger(LOGGER_NAME);

        Echo.initClient(this);

        this.cacheProvider = new RedisCacheProvider(connection);
        this.messagingProvider = new RedisMessagingProvider(connection);
        this.resourceType = resourceType;
        this.resourceId = resourceId;

        this.topic = switch (resourceType) {
            case PROXY -> ProxyImpl.PROXY_TOPIC.formatted(resourceId);
            case SERVER -> ServerImpl.SERVER_TOPIC.formatted(resourceId);
        };

        this.messagingProvider.subscribe(topic, this::onMessageReceive);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Map<UUID, Instant>> getAllUsersAsync() {
        return this.getUserMap().readAllMapAsync().toCompletableFuture()
                .thenApplyAsync(MapUtils::mapStringToUuidKey);
    }

    private @NotNull RMapAsync<String, Instant> getUserMap() {
        return this.cacheProvider.getAsyncMap(UserImpl.USER_MAP);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Optional<User>> getUserByIdAsync(final @NotNull UUID id) {
        final User user = new UserImpl(id);
        final CompletableFuture<Optional<User>> future = new CompletableFuture<>();

        user.getUsernameAsync().thenAccept(username -> future.complete(username.map(u -> user)));
        return future;
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Optional<User>> getUserByUsernameAsync(final @NotNull String username) {
        final RMapAsync<String, String> usernameToIdMap = this.getPlayerUsernameToIdMap();

        return usernameToIdMap.getAsync(username.toLowerCase(Locale.ROOT)).toCompletableFuture().thenApply(idStr -> {
            if (idStr == null)
                return null;
            return UUID.fromString(idStr);
        }).thenCompose(this::getUserByIdAsync);
    }

    @Override
    public @NotNull CompletableFuture<Void> registerUserUsernameAsync(@NotNull UUID id, @NotNull String username) {
        return this.getPlayerUsernameToIdMap().putAsync(username.toLowerCase(Locale.ROOT), id.toString()).toCompletableFuture().thenApply(aVoid -> null);
    }

    @Override
    public @NotNull CompletableFuture<Void> unregisterUserUsernameAsync(@NotNull User user) {
        return CompletableFuture.allOf(
                user.getUsernameAsync().thenComposeAsync(usernameOpt -> {
                    if (usernameOpt.isEmpty())
                        return CompletableFuture.completedFuture(null);

                    final String username = usernameOpt.get();

                    return this.getPlayerUsernameToIdMap()
                            .removeAsync(username.toLowerCase(Locale.ROOT))
                            .toCompletableFuture();
                })
        );
    }

    private @NotNull RMapAsync<String, String> getPlayerUsernameToIdMap() {
        return this.cacheProvider.getAsyncMap(UserImpl.USERNAME_TO_ID_MAP);
    }

    @Override
    public @NotNull CacheProvider getCacheProvider() {
        return this.cacheProvider;
    }

    @Override
    public @NotNull MessagingProvider getMessagingProvider() {
        return this.messagingProvider;
    }

    @Override
    public @NotNull MessageTarget.Builder newMessageTargetBuilder() {
        return new MessageTargetBuilderImpl();
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Map<String, Instant>> getServersAsync() {
        return this.getServerMap().readAllMapAsync().toCompletableFuture();
    }

    private @NotNull RMapAsync<String, Instant> getServerMap() {
        return this.cacheProvider.getAsyncMap(ServerImpl.SERVER_MAP);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Optional<Server>> getServerByIdAsync(final @NotNull String id) {
        final ServerImpl server = new ServerImpl(id, null);
        return server.stillExistsAsync()
                .thenApplyAsync(exists -> exists ? Optional.of(server) : Optional.empty());
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Map<String, Instant>> getProxiesAsync() {
        return this.getProxyMap().readAllMapAsync().toCompletableFuture();
    }

    private @NotNull RMapAsync<String, Instant> getProxyMap() {
        return this.cacheProvider.getAsyncMap(ProxyImpl.PROXY_MAP);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Optional<Proxy>> getProxyByIdAsync(@NotNull String id) {
        final ProxyImpl proxy = new ProxyImpl(id, null);
        return proxy.stillExists().thenApply(exists -> exists ? Optional.of(proxy) : Optional.empty());
    }

    @Override
    public @NotNull EchoResourceType getCurrentResourceType() {
        return this.resourceType;
    }

    @Override
    public @NotNull Optional<@NotNull String> getCurrentResourceId() {
        return Optional.of(this.resourceId);
    }

    public void onMessageReceive(final @NotNull EchoMessage message) {
        if (!this.messagingProvider.handleReply(message))
            return;
    }

    public void createLocalResource(final @NotNull Address address) {
        switch (this.resourceType) {
            case PROXY -> {
                final ProxyImpl proxy = new ProxyImpl(this.resourceId, address);

                proxy.setPropertyAsync(AbstractPropertyHolder.CREATION_TIME_KEY, Instant.now());

                this.logger.info("Created proxy %s".formatted(proxy.getId()));
            }
            case SERVER -> {
                final ServerImpl server = new ServerImpl(this.resourceId, address);

                server.setPropertyAsync(AbstractPropertyHolder.CREATION_TIME_KEY, Instant.now());

                this.logger.info("Created server %s".formatted(server.getId()));
            }
        }
    }

    public void advertiseLocalResource() {
        switch (this.resourceType) {
            case PROXY -> {

            }
            case SERVER -> {
                this.sendLocalServerStatus(ServerStatusNotification.Status.REGISTERED);

                this.logger.info("Advertised server %s".formatted(this.getCurrentResourceId().orElse(null)));
            }
        }
    }

    private @NotNull CompletableFuture<Void> sendLocalServerStatus(final @NotNull ServerStatusNotification.Status status) {
        return this.getServerByIdAsync(Objects.requireNonNull(this.getCurrentResourceId().orElse(null)))
                .thenApply(serverOpt -> {
                    if (serverOpt.isEmpty())
                        throw new UnknownServerException(this.getCurrentResourceId().orElseThrow());
                    return serverOpt.get();
                }).thenCombine(this.newMessageTargetBuilder()
                        .withAllProxies(), (server, builder) -> {
                    final ServerStatusNotification notification = new ServerStatusNotification(server, status);
                    return server.publishMessage(builder.build(), notification);
                }).thenAccept(aVoid -> {});
    }

    public static @NotNull EchoClientImpl autoInit(@NotNull RedisConnection connection,
                                                   @Nullable EchoResourceType resourceType) throws Exception {
        String resourceId;
        Address resourceAddress;

        try {
            if (resourceType == null)
                resourceType = Objects.requireNonNull(EnvUtils.getResourceType());
            resourceId = Objects.requireNonNull(EnvUtils.getResourceId());
            resourceAddress = Objects.requireNonNull(EnvUtils.getAddress());
        } catch (final @NotNull Exception exception) {
            throw new IllegalStateException("Failed to load resource type id and/or address from environment variables", exception);
        }

        final EchoClientImpl impl = new EchoClientImpl(
                connection,
                resourceType,
                resourceId
        );
        impl.createLocalResource(resourceAddress);
        switch (resourceType) {
            case PROXY -> impl.registerProxy(resourceId).join();
            case SERVER -> impl.registerServer(resourceId).join();
        }
        impl.advertiseLocalResource();


        return impl;
    }

    public @NotNull CompletableFuture<@NotNull Instant> registerProxy(final @NotNull String id) {
        final Instant creationTime = Instant.now();
        return this.getProxyMap().fastPutAsync(id, creationTime).toCompletableFuture()
                .thenApplyAsync(aVoid -> creationTime);
    }

    public @NotNull CompletableFuture<Void> unregisterProxy(final @NotNull String id) {
        return this.getProxyMap().fastRemoveAsync(id).toCompletableFuture()
                .thenRunAsync(() -> {});
    }

    public @NotNull CompletableFuture<@NotNull Instant> registerServer(final @NotNull String id) {
        final Instant creationTime = Instant.now();
        return this.getServerMap().putAsync(id, creationTime).toCompletableFuture()
                .thenApply(aVoid -> creationTime);
    }

    public @NotNull CompletableFuture<Void> unregisterServer(final @NotNull String id) {
        return this.getServerMap().removeAsync(id).toCompletableFuture().thenApply(aVoid -> null);
    }

    @Override
    public void shutdown() {
        Optional<? extends Cleanable> clOpt = (switch (this.resourceType) {
            case PROXY -> this.getProxyByIdAsync(this.getCurrentResourceId().orElseThrow()).join();
            case SERVER -> this.getServerByIdAsync(this.getCurrentResourceId().orElseThrow()).join();
        });

        switch (this.resourceType) {
            case PROXY -> this.unregisterProxy(this.getCurrentResourceId().orElseThrow());
            case SERVER -> {
                this.sendLocalServerStatus(ServerStatusNotification.Status.UNREGISTERED).join();
                this.unregisterServer(this.getCurrentResourceId().orElseThrow()).join();
            }
        }

        if (clOpt.isEmpty()) {
            throw new UnknownResourceException("resource", this.getCurrentResourceId().orElseThrow());
        }

        logger.info("Cleaning up local resource in current thread");
        final Cleanable cl = clOpt.get();;
        cl.cleanup().join();
    }

    @Override
    public @NotNull String getLocalTopic() {
        return this.topic;
    }

    @Override
    public @NotNull CompletableFuture<User> createUserAsync(final @NotNull UUID uuid,
                                                            final @NotNull String username,
                                                            final @NotNull String proxyId) {
        final User user = new UserImpl(uuid);
        return CompletableFuture.allOf(
                user.setPropertyAsync(User.PROPERTY_USERNAME, username),
                user.setPropertyAsync(User.PROPERTY_CURRENT_PROXY_ID, proxyId),
                user.setPropertyAsync(AbstractPropertyHolder.CREATION_TIME_KEY, Instant.now()),
                this.registerUserUsernameAsync(uuid, username),
                this.getUserMap().fastPutAsync(uuid.toString(), Instant.now()).toCompletableFuture()
        ).thenApply(aVoid -> user);
    }

    @Override
    public @NotNull CompletableFuture<Void> destroyUserAsync(final @NotNull User user) {
        return CompletableFuture.allOf(
                this.unregisterUserUsernameAsync(user),
                this.registerUserInServerAsync(user, null),
                this.getUserMap().fastRemoveAsync(user.getId().toString()).toCompletableFuture()
        );
    }

    @Override
    public @NotNull CompletableFuture<Void> registerUserInServerAsync(@NotNull User user, @Nullable Server server) {
        CompletableFuture<Void> unregisterFuture = user.getCurrentServerAsync()
                    .thenComposeAsync(sOpt -> sOpt.<java.util.concurrent.CompletionStage<Boolean>>map(value -> value.unregisterUserAsync(user)).orElseGet(() -> CompletableFuture.completedFuture(null)))
                    .thenRun(() -> {});

        if (server == null)
            return unregisterFuture;

        return CompletableFuture.allOf(
                unregisterFuture,
                server.registerUserAsync(user),
                user.setPropertyAsync(User.PROPERTY_CURRENT_SERVER_ID, server.getId())
        );
    }
}
