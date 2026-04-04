package fr.codinbox.echo.core.cache;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.cache.CacheMap;
import fr.codinbox.echo.api.cache.CacheProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class RedisCacheProvider implements CacheProvider {

    private final @NotNull RedisConnection connection;
    private final @NotNull AtomicBoolean shutdown = new AtomicBoolean(false);

    public RedisCacheProvider(final @NotNull RedisConnection connection) {
        this.connection = connection;
    }

    @Override
    public @NotNull CompletableFuture<Void> init() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NotNull CompletableFuture<Void> shutdown() {
        shutdown.set(true);
        return CompletableFuture.completedFuture(null);
    }

    private @NotNull RedissonClient client() {
        return this.connection.getClient();
    }

    @Override
    public @NotNull <T> CompletableFuture<@Nullable T> getObject(final @NotNull String key) {
        final RBucket<T> bucket = client().getBucket(key);
        return bucket.getAsync().toCompletableFuture();
    }

    @Override
    public @NotNull <T> CompletableFuture<Void> setObject(final @NotNull String key, final @NotNull T value) {
        final RBucket<T> bucket = client().getBucket(key);
        return bucket.setAsync(value).toCompletableFuture();
    }

    @Override
    public @NotNull CompletableFuture<Boolean> expireObject(final @NotNull String key, final @NotNull Instant instant) {
        final RBucket<Object> bucket = client().getBucket(key);
        return bucket.expireAsync(instant).toCompletableFuture();
    }

    @Override
    public @NotNull CompletableFuture<Boolean> expireObject(final @NotNull String key, final @NotNull Duration duration) {
        final RBucket<Object> bucket = client().getBucket(key);
        return bucket.expireAsync(duration).toCompletableFuture();
    }

    @Override
    public @NotNull CompletableFuture<Boolean> deleteObject(final @NotNull String key) {
        final RBucket<Object> bucket = client().getBucket(key);
        return bucket.deleteAsync().toCompletableFuture();
    }

    @Override
    public @NotNull CompletableFuture<Long> getObjectRemainingTimeToLive(final @NotNull String key) {
        final RBucket<Object> bucket = client().getBucket(key);
        return bucket.remainTimeToLiveAsync().toCompletableFuture();
    }

    @Override
    public @NotNull CompletableFuture<Boolean> hasObject(final @NotNull String key) {
        return this.getObject(key).thenApply(Objects::nonNull);
    }

    @Override
    public <K, V> @NotNull CacheMap<K, V> getMap(final @NotNull String key) {
        return new RedisCacheMap<>(client().getMap(key));
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Set<String>> getKeys(final @NotNull String pattern) {
        return CompletableFuture.supplyAsync(() -> StreamSupport
                .stream(client().getKeys().getKeysByPattern(pattern).spliterator(), true)
                .collect(Collectors.toSet()));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> withLock(final @NotNull String key,
                                                         long waitTime,
                                                         long leaseTime,
                                                         final @NotNull TimeUnit unit,
                                                         final @NotNull Supplier<@NotNull CompletableFuture<Void>> action) {
        return CompletableFuture.supplyAsync(() -> {
            final RLock lock = client().getLock(key);
            boolean acquired;
            try {
                acquired = lock.tryLock(waitTime, leaseTime, unit);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (!acquired)
                return false;
            return lock;
        }).thenCompose(result -> {
            if (result instanceof Boolean b)
                return CompletableFuture.completedFuture(b);
            final RLock lock = (RLock) result;
            return action.get().handle((v, ex) -> {
                try {
                    if (lock.isHeldByCurrentThread())
                        lock.unlock();
                } catch (final Exception unlockEx) {
                    // Best effort unlock
                }
                if (ex != null)
                    throw ex instanceof RuntimeException re ? re : new RuntimeException(ex);
                return true;
            });
        });
    }

}
