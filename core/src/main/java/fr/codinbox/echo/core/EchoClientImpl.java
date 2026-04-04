package fr.codinbox.echo.core;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.cache.RedisCacheProvider;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessageTarget;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.messaging.impl.ServerStatusNotification;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.api.utils.EnvUtils;
import fr.codinbox.echo.core.cache.RedisRedisCacheProvider;
import fr.codinbox.echo.core.messaging.MessageTargetBuilderImpl;
import fr.codinbox.echo.core.messaging.provider.RedisMessagingProvider;
import fr.codinbox.echo.core.property.AbstractPropertyHolder;
import fr.codinbox.echo.core.proxy.ProxyImpl;
import fr.codinbox.echo.core.server.ServerImpl;
import fr.codinbox.echo.core.user.UserImpl;
import fr.codinbox.echo.core.utils.MapUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RLock;
import org.redisson.api.RMapAsync;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EchoClientImpl implements EchoClient {

    private static final @NotNull String LOGGER_NAME = "echo";

    private static final @NotNull String HEARTBEAT_KEY_FORMAT = "heartbeat:%s:%s";
    private static final @NotNull String SUSPECT_KEY_FORMAT = "suspect:%s:%s";
    private static final @NotNull String CLEANUP_LOCK_FORMAT = "cleanup:%s:%s";

    private final @NotNull Logger logger;

    private final @NotNull RedissonClient redissonClient;
    private final @NotNull RedisCacheProvider redisCacheProvider;
    private final @NotNull MessagingProvider messagingProvider;
    private final @NotNull EchoResourceType resourceType;
    private final @NotNull String resourceId;
    private final @NotNull String topic;

    private final long heartbeatTtlSeconds;
    private final long heartbeatIntervalSeconds;
    private final long scanIntervalSeconds;
    private final boolean cleanupEnabled;
    private final @NotNull ScheduledExecutorService scheduler;

    public EchoClientImpl(final @NotNull RedisConnection connection,
                          final @NotNull EchoResourceType resourceType,
                          final @NotNull String resourceId) {
        this(connection, resourceType, resourceId,
                EnvUtils.getHeartbeatTtl(), EnvUtils.getHeartbeatInterval(),
                EnvUtils.getScanInterval(),
                resourceType == EchoResourceType.PROXY || EnvUtils.isHealthcheckCleanupEnabled());
    }

    public EchoClientImpl(final @NotNull RedisConnection connection,
                          final @NotNull EchoResourceType resourceType,
                          final @NotNull String resourceId,
                          final long heartbeatTtlSeconds,
                          final long heartbeatIntervalSeconds,
                          final long scanIntervalSeconds,
                          final boolean cleanupEnabled) {
        this.logger = Logger.getLogger(LOGGER_NAME);

        Echo.initClient(this);

        this.redissonClient = connection.getClient();
        this.redisCacheProvider = new RedisRedisCacheProvider(connection);
        this.messagingProvider = new RedisMessagingProvider(connection);
        this.resourceType = resourceType;
        this.resourceId = resourceId;

        this.heartbeatTtlSeconds = heartbeatTtlSeconds;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.scanIntervalSeconds = scanIntervalSeconds;
        this.cleanupEnabled = cleanupEnabled;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "echo-healthcheck");
            t.setDaemon(true);
            return t;
        });

        this.topic = switch (resourceType) {
            case PROXY -> ProxyImpl.PROXY_TOPIC.formatted(resourceId);
            case SERVER -> ServerImpl.SERVER_TOPIC.formatted(resourceId);
        };

        this.messagingProvider.subscribe(topic, this::onMessageReceive);
        this.messagingProvider.subscribe(MessageTarget.BROADCAST_TOPIC, this::onMessageReceive);
    }

    @Override
    public @NotNull EchoFuture<@NotNull Map<UUID, Long>> getAllUsers() {
        return EchoFuture.of(this.getUserMap().readAllMapAsync().toCompletableFuture()
                .thenApplyAsync(MapUtils::mapStringToUuidKey));
    }

    private @NotNull RMapAsync<String, Long> getUserMap() {
        return this.redisCacheProvider.getAsyncMap(UserImpl.USER_MAP);
    }

    @Override
    public @NotNull EchoFuture<@NotNull Optional<User>> getUserById(final @NotNull UUID id) {
        final User user = new UserImpl(id);
        final CompletableFuture<Optional<User>> future = new CompletableFuture<>();

        user.getUsername().thenAccept(username -> future.complete(username.map(u -> user)));
        return EchoFuture.of(future);
    }

    @Override
    public @NotNull EchoFuture<@NotNull Optional<User>> getUserByUsername(final @NotNull String username) {
        final RMapAsync<String, String> usernameToIdMap = this.getPlayerUsernameToIdMap();

        return EchoFuture.of(usernameToIdMap.getAsync(username.toLowerCase(Locale.ROOT)).toCompletableFuture().thenApply(idStr -> {
            if (idStr == null)
                return null;
            return UUID.fromString(idStr);
        }).thenCompose(this::getUserById));
    }

    @Override
    public @NotNull EchoFuture<Void> registerUserUsername(@NotNull UUID id, @NotNull String username) {
        return EchoFuture.of(this.getPlayerUsernameToIdMap().putAsync(username.toLowerCase(Locale.ROOT), id.toString()).toCompletableFuture().thenApply(aVoid -> null));
    }

    @Override
    public @NotNull EchoFuture<Void> unregisterUserUsername(@NotNull User user) {
        return EchoFuture.of(CompletableFuture.allOf(
                user.getUsername().thenComposeAsync(usernameOpt -> {
                    if (usernameOpt.isEmpty())
                        return CompletableFuture.completedFuture(null);

                    final String username = usernameOpt.get();

                    return this.getPlayerUsernameToIdMap()
                            .removeAsync(username.toLowerCase(Locale.ROOT))
                            .toCompletableFuture();
                })
        ));
    }

    private @NotNull RMapAsync<String, String> getPlayerUsernameToIdMap() {
        return this.redisCacheProvider.getAsyncMap(UserImpl.USERNAME_TO_ID_MAP);
    }

    @Override
    public @NotNull RedisCacheProvider getCacheProvider() {
        return this.redisCacheProvider;
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
    public @NotNull EchoFuture<@NotNull Map<String, Long>> getServers() {
        return EchoFuture.of(this.getServerMap().readAllMapAsync().toCompletableFuture());
    }

    private @NotNull RMapAsync<String, Long> getServerMap() {
        return this.redisCacheProvider.getAsyncMap(ServerImpl.SERVER_MAP);
    }

    @Override
    public @NotNull EchoFuture<@NotNull Optional<Server>> getServerById(final @NotNull String id) {
        final ServerImpl server = new ServerImpl(id, null);
        return EchoFuture.of(server.stillExists()
                .thenApplyAsync(exists -> exists ? Optional.of(server) : Optional.empty()));
    }

    @Override
    public @NotNull EchoFuture<@NotNull Map<String, Long>> getProxies() {
        return EchoFuture.of(this.getProxyMap().readAllMapAsync().toCompletableFuture());
    }

    private @NotNull RMapAsync<String, Long> getProxyMap() {
        return this.redisCacheProvider.getAsyncMap(ProxyImpl.PROXY_MAP);
    }

    @Override
    public @NotNull EchoFuture<@NotNull Optional<Proxy>> getProxyById(@NotNull String id) {
        final ProxyImpl proxy = new ProxyImpl(id, null);
        return EchoFuture.of(proxy.stillExists().thenApply(exists -> exists ? Optional.of(proxy) : Optional.empty()));
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
        final String heartbeatKey = getHeartbeatKey(this.resourceType, this.resourceId);

        switch (this.resourceType) {
            case PROXY -> {
                final ProxyImpl proxy = new ProxyImpl(this.resourceId, address);

                proxy.setProperty(AbstractPropertyHolder.CREATION_TIME_KEY, Instant.now().toEpochMilli());

                this.logger.info("Created proxy %s".formatted(proxy.getId()));
            }
            case SERVER -> {
                final ServerImpl server = new ServerImpl(this.resourceId, address);

                server.setProperty(AbstractPropertyHolder.CREATION_TIME_KEY, Instant.now().toEpochMilli());

                this.logger.info("Created server %s".formatted(server.getId()));
            }
        }

        // Emit first heartbeat
        this.redisCacheProvider.setObject(heartbeatKey, Instant.now().toEpochMilli())
                .thenCompose(v -> this.redisCacheProvider.expireObject(heartbeatKey,
                        Instant.now().plusSeconds(this.heartbeatTtlSeconds)))
                .join();
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

    private void sendLocalServerStatus(final @NotNull ServerStatusNotification.Status status) {
        sendLocalServerStatusDirect(status);
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
        impl.startHealthcheck();

        return impl;
    }

    public @NotNull CompletableFuture<@NotNull Long> registerProxy(final @NotNull String id) {
        final Instant creationTime = Instant.now();
        return this.getProxyMap().fastPutAsync(id, creationTime.toEpochMilli()).toCompletableFuture()
                .thenApplyAsync(aVoid -> creationTime.toEpochMilli());
    }

    public @NotNull CompletableFuture<Void> unregisterProxy(final @NotNull String id) {
        return this.getProxyMap().fastRemoveAsync(id).toCompletableFuture()
                .thenRunAsync(() -> {});
    }

    public @NotNull CompletableFuture<@NotNull Instant> registerServer(final @NotNull String id) {
        final Instant creationTime = Instant.now();
        return this.getServerMap().putAsync(id, creationTime.toEpochMilli()).toCompletableFuture()
                .thenApply(aVoid -> creationTime);
    }

    public @NotNull CompletableFuture<Void> unregisterServer(final @NotNull String id) {
        return this.getServerMap().removeAsync(id).toCompletableFuture().thenApply(aVoid -> null);
    }

    @Override
    public void shutdown() {
        this.scheduler.shutdownNow();

        // Delete heartbeat key before stillExists() check (otherwise we'd fail our own check)
        final String heartbeatKey = getHeartbeatKey(this.resourceType, this.resourceId);
        this.redisCacheProvider.deleteObject(heartbeatKey).join();

        switch (this.resourceType) {
            case PROXY -> {
                this.unregisterProxy(this.getCurrentResourceId().orElseThrow());
                final ProxyImpl proxy = new ProxyImpl(this.resourceId, null);
                this.logger.info("Cleaning up local resource in current thread");
                proxy.cleanup().await();
            }
            case SERVER -> {
                this.sendLocalServerStatusDirect(ServerStatusNotification.Status.UNREGISTERED);
                this.unregisterServer(this.getCurrentResourceId().orElseThrow()).join();
                final ServerImpl server = new ServerImpl(this.resourceId, null);
                this.logger.info("Cleaning up local resource in current thread");
                server.cleanup().await();
            }
        }
    }

    @Override
    public @NotNull String getLocalTopic() {
        return this.topic;
    }

    @Override
    public @NotNull EchoFuture<@NotNull User> createUser(final @NotNull UUID uuid,
                                                          final @NotNull String username,
                                                          final @NotNull String proxyId) {
        final User user = new UserImpl(uuid);
        return EchoFuture.of(CompletableFuture.allOf(
                user.setProperty(User.PROPERTY_USERNAME, username),
                user.setProperty(User.PROPERTY_CURRENT_PROXY_ID, proxyId),
                user.setProperty(AbstractPropertyHolder.CREATION_TIME_KEY, Instant.now().toEpochMilli()),
                this.registerUserUsername(uuid, username),
                this.getUserMap().fastPutAsync(uuid.toString(), Instant.now().toEpochMilli()).toCompletableFuture()
        ).thenApply(aVoid -> user));
    }

    @Override
    public @NotNull EchoFuture<Void> destroyUser(final @NotNull User user) {
        return EchoFuture.of(CompletableFuture.allOf(
                this.unregisterUserUsername(user),
                this.registerUserInServer(user, null),
                this.getUserMap().fastRemoveAsync(user.getId().toString()).toCompletableFuture(),
                user.cleanup()
        ));
    }

    @Override
    public @NotNull EchoFuture<Void> registerUserInServer(@NotNull User user, @Nullable Server server) {
        CompletableFuture<Void> unregisterFuture = user.getCurrentServer()
                    .thenComposeAsync(sOpt -> sOpt.<java.util.concurrent.CompletionStage<Boolean>>map(value -> value.unregisterUser(user)).orElseGet(() -> CompletableFuture.completedFuture(null)))
                    .thenRun(() -> {});

        if (server == null)
            return EchoFuture.of(unregisterFuture);

        return EchoFuture.of(unregisterFuture.thenCompose(v -> CompletableFuture.allOf(
                server.registerUser(user),
                user.setProperty(User.PROPERTY_CURRENT_SERVER_ID, server.getId())
        )));
    }

    // --- Healthcheck ---

    private static @NotNull String getHeartbeatKey(final @NotNull EchoResourceType type, final @NotNull String id) {
        return HEARTBEAT_KEY_FORMAT.formatted(type.name().toLowerCase(Locale.ROOT), id);
    }

    private static @NotNull String getSuspectKey(final @NotNull EchoResourceType type, final @NotNull String id) {
        return SUSPECT_KEY_FORMAT.formatted(type.name().toLowerCase(Locale.ROOT), id);
    }

    private static @NotNull String getCleanupLockKey(final @NotNull EchoResourceType type, final @NotNull String id) {
        return CLEANUP_LOCK_FORMAT.formatted(type.name().toLowerCase(Locale.ROOT), id);
    }

    public void startHealthcheck() {
        // Heartbeat task
        this.scheduler.scheduleAtFixedRate(this::emitHeartbeat,
                this.heartbeatIntervalSeconds, this.heartbeatIntervalSeconds, TimeUnit.SECONDS);

        // Scanner task (only if cleanup is enabled)
        if (this.cleanupEnabled) {
            this.scheduler.scheduleAtFixedRate(this::scanForDeadResources,
                    this.scanIntervalSeconds, this.scanIntervalSeconds, TimeUnit.SECONDS);
        }

        this.logger.info("Healthcheck started (heartbeat=%ds, ttl=%ds, scan=%ds, cleanup=%s)"
                .formatted(this.heartbeatIntervalSeconds, this.heartbeatTtlSeconds,
                        this.scanIntervalSeconds, this.cleanupEnabled));
    }

    private void emitHeartbeat() {
        try {
            final String key = getHeartbeatKey(this.resourceType, this.resourceId);
            this.redisCacheProvider.setObject(key, Instant.now().toEpochMilli())
                    .thenCompose(v -> this.redisCacheProvider.expireObject(key,
                            Instant.now().plusSeconds(this.heartbeatTtlSeconds)))
                    .join();
        } catch (final Exception e) {
            this.logger.log(Level.SEVERE, "Failed to emit heartbeat", e);
        }
    }

    public void scanForDeadResources() {
        try {
            // Scan servers
            final Map<String, Long> servers = this.getServerMap().readAllMapAsync()
                    .toCompletableFuture().join();
            for (final String serverId : servers.keySet()) {
                if (serverId.equals(this.resourceId) && this.resourceType == EchoResourceType.SERVER)
                    continue;
                checkResource(EchoResourceType.SERVER, serverId);
            }

            // Scan proxies
            final Map<String, Long> proxies = this.getProxyMap().readAllMapAsync()
                    .toCompletableFuture().join();
            for (final String proxyId : proxies.keySet()) {
                if (proxyId.equals(this.resourceId) && this.resourceType == EchoResourceType.PROXY)
                    continue;
                checkResource(EchoResourceType.PROXY, proxyId);
            }
        } catch (final Exception e) {
            this.logger.log(Level.SEVERE, "Failed to scan for dead resources", e);
        }
    }

    private void checkResource(final @NotNull EchoResourceType type, final @NotNull String id) {
        final String heartbeatKey = getHeartbeatKey(type, id);
        final String suspectKey = getSuspectKey(type, id);

        final boolean heartbeatExists = this.redisCacheProvider.hasObject(heartbeatKey).join();
        if (heartbeatExists)
            return;

        // No heartbeat — check if already suspected
        final boolean isSuspect = this.redisCacheProvider.hasObject(suspectKey).join();
        if (!isSuspect) {
            // First detection: mark as suspect
            this.redisCacheProvider.setObject(suspectKey, Instant.now().toEpochMilli())
                    .thenCompose(v -> this.redisCacheProvider.expireObject(suspectKey,
                            Instant.now().plusSeconds(this.scanIntervalSeconds * 2)))
                    .join();
            this.logger.warning("Resource %s:%s has no heartbeat, marked as suspect".formatted(type.name(), id));
            return;
        }

        // Second detection: confirmed dead, cleanup
        this.logger.warning("Resource %s:%s confirmed dead, initiating cleanup".formatted(type.name(), id));
        this.cleanupDeadResource(type, id);
    }

    private void cleanupDeadResource(final @NotNull EchoResourceType type, final @NotNull String id) {
        final String lockKey = getCleanupLockKey(type, id);
        final RLock lock = this.redissonClient.getLock(lockKey);

        boolean acquired;
        try {
            acquired = lock.tryLock(0, 30, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (!acquired) {
            this.logger.log(Level.FINE, "Cleanup lock for %s:%s already held by another node".formatted(type.name(), id));
            return;
        }

        try {
            // Re-check heartbeat under lock (may have come back)
            final String heartbeatKey = getHeartbeatKey(type, id);
            if (this.redisCacheProvider.hasObject(heartbeatKey).join()) {
                this.logger.info("Resource %s:%s heartbeat restored, skipping cleanup".formatted(type.name(), id));
                return;
            }

            switch (type) {
                case SERVER -> cleanupDeadServer(id);
                case PROXY -> cleanupDeadProxy(id);
            }

            // Remove suspect key
            this.redisCacheProvider.deleteObject(getSuspectKey(type, id)).join();

            this.logger.info("Cleanup completed for %s:%s".formatted(type.name(), id));
        } catch (final Exception e) {
            this.logger.log(Level.SEVERE, "Failed to cleanup dead resource %s:%s".formatted(type.name(), id), e);
        } finally {
            try {
                if (lock.isHeldByCurrentThread())
                    lock.unlock();
            } catch (final Exception e) {
                this.logger.log(Level.FINE, "Failed to unlock cleanup lock for %s:%s".formatted(type.name(), id), e);
            }
        }
    }

    private void cleanupDeadServer(final @NotNull String serverId) {
        final ServerImpl server = new ServerImpl(serverId, null);

        // Cleanup orphaned users
        cleanupOrphanedServerUsers(server, serverId);

        // Send UNREGISTERED notification to proxies
        sendServerStatusNotification(serverId, ServerStatusNotification.Status.UNREGISTERED);

        // Remove from servers map
        this.unregisterServer(serverId).join();

        // Cleanup server resources (address, properties)
        server.cleanup().await();

        // Clear connected users map
        server.clearUsers().await();
    }

    private void cleanupDeadProxy(final @NotNull String proxyId) {
        final ProxyImpl proxy = new ProxyImpl(proxyId, null);

        // Cleanup orphaned users
        cleanupOrphanedProxyUsers(proxy, proxyId);

        // Remove from proxies map
        this.unregisterProxy(proxyId).join();

        // Cleanup proxy resources (address, properties)
        proxy.cleanup().await();

        // Clear connected users map
        proxy.clearUsers().await();
    }

    private void cleanupOrphanedServerUsers(final @NotNull ServerImpl server, final @NotNull String serverId) {
        try {
            final Map<UUID, Long> connectedUsers = server.getConnectedUsers().await();
            for (final UUID userId : connectedUsers.keySet()) {
                final User user = new UserImpl(userId);
                final Optional<String> currentServerId = user.getCurrentServerId().await();
                if (currentServerId.isPresent() && currentServerId.get().equals(serverId)) {
                    this.destroyUser(user).await();
                }
            }
        } catch (final Exception e) {
            this.logger.log(Level.WARNING, "Failed to cleanup orphaned users for server %s".formatted(serverId), e);
        }
    }

    private void cleanupOrphanedProxyUsers(final @NotNull ProxyImpl proxy, final @NotNull String proxyId) {
        try {
            final Map<UUID, Long> connectedUsers = proxy.getConnectedUsers().await();
            for (final UUID userId : connectedUsers.keySet()) {
                final User user = new UserImpl(userId);
                final Optional<String> currentProxyId = user.getCurrentProxyId().await();
                if (currentProxyId.isPresent() && currentProxyId.get().equals(proxyId)) {
                    this.destroyUser(user).await();
                }
            }
        } catch (final Exception e) {
            this.logger.log(Level.WARNING, "Failed to cleanup orphaned users for proxy %s".formatted(proxyId), e);
        }
    }

    private void sendServerStatusNotification(final @NotNull String serverId,
                                               final @NotNull ServerStatusNotification.Status status) {
        try {
            final ServerImpl server = new ServerImpl(serverId, null);
            final MessageTarget target = this.newMessageTargetBuilder()
                    .withAllProxies().await()
                    .build();
            final ServerStatusNotification notification = new ServerStatusNotification(server, status);
            server.publishMessage(target, notification).await();
        } catch (final Exception e) {
            this.logger.log(Level.WARNING, "Failed to send status notification for server %s".formatted(serverId), e);
        }
    }

    private void sendLocalServerStatusDirect(final @NotNull ServerStatusNotification.Status status) {
        final String serverId = this.getCurrentResourceId().orElseThrow();
        sendServerStatusNotification(serverId, status);
    }
}
