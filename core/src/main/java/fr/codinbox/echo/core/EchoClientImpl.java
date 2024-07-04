package fr.codinbox.echo.core;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.cache.CacheProvider;
import fr.codinbox.echo.api.exception.UnknownResourceException;
import fr.codinbox.echo.api.exception.resource.UnknownServerException;
import fr.codinbox.echo.api.id.Identifiable;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessageTarget;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.messaging.impl.ServerStatusNotification;
import fr.codinbox.echo.api.property.PropertyHolder;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.api.utils.Cleanable;
import fr.codinbox.echo.api.utils.EnvUtils;
import fr.codinbox.echo.api.utils.NullableUtils;
import fr.codinbox.echo.core.cache.RedisCacheProvider;
import fr.codinbox.echo.core.messaging.MessageTargetBuilderImpl;
import fr.codinbox.echo.core.messaging.provider.RedisMessagingProvider;
import fr.codinbox.echo.core.property.AbstractPropertyHolder;
import fr.codinbox.echo.core.proxy.ProxyImpl;
import fr.codinbox.echo.core.server.ServerImpl;
import fr.codinbox.echo.core.user.UserImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.LogManager;
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
    public @NotNull CompletableFuture<@NotNull Map<String, Instant>> getConnectedUsers() {
        return CompletableFuture.completedFuture(Map.copyOf(this.getUserMap()));
    }

    private @NotNull Map<String, Instant> getUserMap() {
        return this.cacheProvider.getMap(UserImpl.USER_MAP);
    }

    @Override
    public @NotNull CompletableFuture<@Nullable User> getUserById(@NotNull UUID id) {
        final User user = new UserImpl(id);
        final CompletableFuture<@Nullable User> future = new CompletableFuture<>();

        user.getUsername().thenAccept(username -> future.complete(username != null ? user : null));
        return future;
    }

    @Override
    public @NotNull CompletableFuture<@Nullable User> getUserByUsername(@NotNull String username) {
        final Map<String, String> usernameToIdMap = this.getPlayerUsernameToIdMap();
        try {
            final UUID id = UUID.fromString(usernameToIdMap.get(username));
            return this.getUserById(id);
        } catch (IllegalArgumentException exception) {
            usernameToIdMap.remove(username);
            throw exception;
        }
    }

    private @NotNull Map<String, String> getPlayerUsernameToIdMap() {
        return this.cacheProvider.getMap(UserImpl.USERNAME_TO_ID_MAP);
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
    public @NotNull CompletableFuture<@NotNull Map<String, Instant>> getServers() {
        return CompletableFuture.completedFuture(Map.copyOf(this.getServerMap()));
    }

    private @NotNull Map<String, Instant> getServerMap() {
        return this.cacheProvider.getMap(ServerImpl.SERVER_MAP);
    }

    @Override
    public @NotNull CompletableFuture<@Nullable Server> getServerById(final @NotNull String id) {
        final ServerImpl server = new ServerImpl(id, null);
        return server.stillExists().thenApply(exists -> exists ? server : null);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Map<String, Instant>> getProxies() {
        return CompletableFuture.completedFuture(Map.copyOf(this.getProxyMap()));
    }

    private @NotNull Map<String, Instant> getProxyMap() {
        return this.cacheProvider.getMap(ProxyImpl.PROXY_MAP);
    }

    @Override
    public @NotNull CompletableFuture<@Nullable Proxy> getProxyById(@NotNull String id) {
        final ProxyImpl proxy = new ProxyImpl(id, null);
        return proxy.stillExists().thenApply(exists -> exists ? proxy : null);
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

                proxy.setProperty(AbstractPropertyHolder.CREATION_TIME_KEY, Instant.now());

                this.logger.info("Created proxy %s".formatted(proxy.getId()));
            }
            case SERVER -> {
                final ServerImpl server = new ServerImpl(this.resourceId, address);

                server.setProperty(AbstractPropertyHolder.CREATION_TIME_KEY, Instant.now());

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
        return this.getServerById(Objects.requireNonNull(this.getCurrentResourceId().orElse(null))).thenApply(server -> {
            NullableUtils.requireNonNull(
                    server,
                    UnknownServerException.class,
                    this.getCurrentResourceId().orElse(null)
            );
            assert server != null;
            return server;
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
        impl.advertiseLocalResource();

        switch (resourceType) {
            case PROXY -> impl.registerProxy(resourceId).join();
            case SERVER -> impl.registerServer(resourceId).join();
        }

        return impl;
    }

    public @NotNull CompletableFuture<@NotNull Instant> registerProxy(final @NotNull String id) {
        final Instant creationTime = Instant.now();

        this.getProxyMap().put(id, creationTime);
        return CompletableFuture.completedFuture(creationTime);
    }

    public @NotNull CompletableFuture<Void> unregisterProxy(final @NotNull String id) {
        this.getProxyMap().remove(id);
        return CompletableFuture.completedFuture(null);
    }

    public @NotNull CompletableFuture<@NotNull Instant> registerServer(final @NotNull String id) {
        final Instant creationTime = Instant.now();

        this.getServerMap().put(id, creationTime);
        return CompletableFuture.completedFuture(creationTime);
    }

    public @NotNull CompletableFuture<Void> unregisterServer(final @NotNull String id) {
        this.getServerMap().remove(id);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void shutdown() {
        CompletableFuture<? extends Cleanable> cleanableFuture = (switch (this.resourceType) {
            case PROXY -> this.getProxyById(this.getCurrentResourceId().orElseThrow());
            case SERVER -> this.getServerById(this.getCurrentResourceId().orElseThrow());
        });

        switch (this.resourceType) {
            case PROXY -> this.unregisterProxy(this.getCurrentResourceId().orElseThrow());
            case SERVER -> {
                this.sendLocalServerStatus(ServerStatusNotification.Status.UNREGISTERED).join();
                this.unregisterServer(this.getCurrentResourceId().orElseThrow()).join();
            }
        }

        cleanableFuture.thenAccept(cl -> {
            NullableUtils.requireNonNull(
                    cl,
                    UnknownResourceException.class,
                    "resource", this.getCurrentResourceId().orElse(null)
            );
            assert cl != null;
            logger.info("Cleaning up local resource");
            cl.cleanup();
        });

        cleanableFuture.join();
    }

    @Override
    public @NotNull String getLocalTopic() {
        return this.topic;
    }

    @Override
    public @NotNull CompletableFuture<User> createUser(final @NotNull UUID uuid,
                                                       final @NotNull String username,
                                                       final @NotNull String proxyId) {
        final User user = new UserImpl(uuid);
        return user.setProperty(User.PROPERTY_USERNAME, username)
                .thenCompose(aVoid -> user.setProperty(User.PROPERTY_CURRENT_PROXY_ID, proxyId))
                .thenCompose(aVoid -> user.setProperty(AbstractPropertyHolder.CREATION_TIME_KEY, Instant.now()))
                .thenApply(aVoid -> user);
    }

    @Override
    public @NotNull CompletableFuture<Void> destroyUser(@NotNull User user) {
        return user.cleanup()
                .thenRun(() -> this.getUserMap().remove(user.getId().toString()));
    }
}
