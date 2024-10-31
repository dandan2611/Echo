package fr.codinbox.echo.core.cache;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.cache.CacheProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RMapAsync;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class RedisCacheProvider implements CacheProvider {

    private final @NotNull RedisConnection connection;

    public RedisCacheProvider(final @NotNull RedisConnection connection) {
        this.connection = connection;
    }

    @Override
    public @NotNull <T> CompletableFuture<@Nullable T> getObject(@NotNull String key) {
        final RBucket<T> bucket = this.connection.getClient().getBucket(key);
        return bucket.getAsync().toCompletableFuture();
    }

    @Override
    public @NotNull <K, V> RMapAsync<K, V> getAsyncMap(@NotNull String key) {
        final RMap<K, V> map = this.connection.getClient().getMap(key);
        return map;
    }

    @Override
    public @NotNull <T> CompletableFuture<Void> setObject(@NotNull String key, @NotNull T value) {
        final RBucket<T> bucket = this.connection.getClient().getBucket(key);
        return bucket.setAsync(value).toCompletableFuture();
    }

    @Override
    public @NotNull CompletableFuture<Boolean> expireObject(@NotNull String key, final @NotNull Instant instant) {
        final RBucket<Object> bucket = this.connection.getClient().getBucket(key);
        return bucket.expireAsync(instant).toCompletableFuture();
    }

    @Override
    public @NotNull CompletableFuture<Boolean> deleteObject(@NotNull String key) {
        final RBucket<Object> bucket = this.connection.getClient().getBucket(key);
        return bucket.deleteAsync().toCompletableFuture();
    }

    @Override
    public @NotNull CompletableFuture<Long> getObjectRemainingTimeToLive(@NotNull String key) {
        final RBucket<Object> bucket = this.connection.getClient().getBucket(key);
        return bucket.remainTimeToLiveAsync().toCompletableFuture();
    }

    @Override
    public @NotNull CompletableFuture<Boolean> hasObject(@NotNull String key) {
        return this.getObject(key).thenApply(Objects::nonNull);
    }

    @Override
    public @NotNull <K, V> RMap<K, V> getMap(@NotNull String key) {
        return this.connection.getClient().getMap(key);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Set<String>> getKeys(@NotNull String pattern) {
        return CompletableFuture.supplyAsync(() -> StreamSupport
                .stream(this.connection.getClient().getKeys().getKeysByPattern(pattern).spliterator(), true)
                .collect(Collectors.toSet()));
    }
}
