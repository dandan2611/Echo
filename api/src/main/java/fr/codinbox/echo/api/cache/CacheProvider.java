package fr.codinbox.echo.api.cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface CacheProvider {

    <T> @NotNull CompletableFuture<@Nullable T> getObject(final @NotNull String key);

    default <T> @Nullable T getObjectSync(final @NotNull String key) {
        final CompletableFuture<T> object = this.getObject(key);
        return object.join();
    }

    @NotNull <T> CompletableFuture<Void> setObject(final @NotNull String key, final @NotNull T value);

    @NotNull CompletableFuture<Boolean> expireObject(final @NotNull String key, final @NotNull Instant instant);

    @NotNull CompletableFuture<Boolean> deleteObject(final @NotNull String key);

    @NotNull CompletableFuture<Long> getObjectRemainingTimeToLive(final @NotNull String key);

    @NotNull CompletableFuture<Boolean> hasObject(final @NotNull String key);

    <K, V> @NotNull Map<K, V> getMap(final @NotNull String key);

    @NotNull CompletableFuture<@NotNull Set<String>> getKeys(final @NotNull String pattern);

}
