package fr.codinbox.echo.core.cache;

import fr.codinbox.echo.api.cache.CacheMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RMap;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class RedisCacheMap<K, V> implements CacheMap<K, V> {

    private final @NotNull RMap<K, V> rMap;

    public RedisCacheMap(final @NotNull RMap<K, V> rMap) {
        this.rMap = rMap;
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Map<K, V>> readAllAsync() {
        return rMap.readAllMapAsync().toCompletableFuture();
    }

    @Override
    public @NotNull CompletableFuture<@Nullable V> getAsync(final @NotNull K key) {
        return rMap.getAsync(key).toCompletableFuture();
    }

    @Override
    public @NotNull CompletableFuture<@Nullable V> putAsync(final @NotNull K key, final @NotNull V value) {
        return rMap.putAsync(key, value).toCompletableFuture();
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Boolean> fastPutAsync(final @NotNull K key, final @NotNull V value) {
        return rMap.fastPutAsync(key, value).toCompletableFuture();
    }

    @Override
    public @NotNull CompletableFuture<@Nullable V> removeAsync(final @NotNull K key) {
        return rMap.removeAsync(key).toCompletableFuture();
    }

    @SafeVarargs
    @Override
    public final @NotNull CompletableFuture<@NotNull Long> fastRemoveAsync(final @NotNull K... keys) {
        return rMap.fastRemoveAsync(keys).toCompletableFuture();
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Boolean> containsKeyAsync(final @NotNull K key) {
        return rMap.containsKeyAsync(key).toCompletableFuture();
    }

    @Override
    public @NotNull CompletableFuture<Void> clearAsync() {
        return rMap.deleteAsync().toCompletableFuture().thenApply(v -> null);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Long> sizeAsync() {
        return rMap.sizeAsync().toCompletableFuture().thenApply(Integer::longValue);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Set<K>> keysAsync() {
        return rMap.readAllKeySetAsync().toCompletableFuture();
    }

}
