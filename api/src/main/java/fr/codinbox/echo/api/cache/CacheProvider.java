package fr.codinbox.echo.api.cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Technology-agnostic interface for cache operations.
 *
 * <p>Provides key-value storage, distributed maps, key scanning, and distributed locking.
 * Implementations may be backed by Redis, Memcached, or any other suitable store.</p>
 *
 * <p>Access via {@link fr.codinbox.echo.api.EchoClient#getCacheProvider()}:</p>
 *
 * <pre>{@code
 * CacheProvider cache = client.getCacheProvider();
 *
 * // Store and retrieve a value
 * cache.setObject("my:key", "hello").join();
 * String value = cache.<String>getObject("my:key").join();
 *
 * // Set TTL
 * cache.expireObject("my:key", Duration.ofHours(1)).join();
 *
 * // Distributed map
 * CacheMap<String, String> map = cache.getMap("my:hash");
 * map.putAsync("field", "value").join();
 * }</pre>
 *
 * @see fr.codinbox.echo.api.EchoClient#getCacheProvider()
 * @see CacheMap
 */
public interface CacheProvider {

    /**
     * Initializes the cache provider.
     *
     * <p>Called once during startup. Implementations should establish connections
     * and prepare resources.</p>
     *
     * @return a future that completes when initialization is done
     */
    @NotNull CompletableFuture<Void> init();

    /**
     * Shuts down the cache provider, releasing all resources.
     *
     * <p>This method is idempotent — calling it multiple times has no additional effect.</p>
     *
     * @return a future that completes when shutdown is done
     */
    @NotNull CompletableFuture<Void> shutdown();

    /**
     * Gets an object by its key.
     *
     * @param key the key
     * @param <T> the expected value type
     * @return a future that completes with the value, or {@code null} if the key does not exist
     */
    <T> @NotNull CompletableFuture<@Nullable T> getObject(final @NotNull String key);

    /**
     * Gets an object synchronously (blocking).
     *
     * @param key the key
     * @param <T> the expected value type
     * @return the value, or {@code null} if the key does not exist
     */
    default <T> @Nullable T getObjectSync(final @NotNull String key) {
        final CompletableFuture<T> object = this.getObject(key);
        return object.join();
    }

    /**
     * Stores an object under the given key.
     *
     * @param key   the key
     * @param value the value to store
     * @param <T>   the value type
     * @return a future that completes when the value is stored
     */
    @NotNull <T> CompletableFuture<Void> setObject(final @NotNull String key, final @NotNull T value);

    /**
     * Sets an expiration time on a key.
     *
     * @param key     the key
     * @param instant the time at which the key should expire
     * @return a future that completes with {@code true} if the TTL was set
     */
    @NotNull CompletableFuture<Boolean> expireObject(final @NotNull String key, final @NotNull Instant instant);

    /**
     * Sets a time-to-live duration on a key.
     *
     * @param key      the key
     * @param duration the duration after which the key should expire
     * @return a future that completes with {@code true} if the TTL was set
     */
    @NotNull CompletableFuture<Boolean> expireObject(final @NotNull String key, final @NotNull Duration duration);

    /**
     * Deletes a key.
     *
     * @param key the key to delete
     * @return a future that completes with {@code true} if the key was deleted
     */
    @NotNull CompletableFuture<Boolean> deleteObject(final @NotNull String key);

    /**
     * Gets the remaining time-to-live of a key in milliseconds.
     *
     * <p>Returns {@code -1} if the key has no TTL, or {@code -2} if the key does not exist.</p>
     *
     * @param key the key
     * @return a future that completes with the remaining TTL in milliseconds
     */
    @NotNull CompletableFuture<Long> getObjectRemainingTimeToLive(final @NotNull String key);

    /**
     * Checks whether a key exists.
     *
     * @param key the key
     * @return a future that completes with {@code true} if the key exists
     */
    @NotNull CompletableFuture<Boolean> hasObject(final @NotNull String key);

    /**
     * Gets a distributed map by its key.
     *
     * <p>The returned {@link CacheMap} is a lightweight handle — safe to create repeatedly.</p>
     *
     * @param key the key identifying the map
     * @param <K> the map key type
     * @param <V> the map value type
     * @return the cache map
     */
    <K, V> @NotNull CacheMap<K, V> getMap(final @NotNull String key);

    /**
     * Finds all keys matching a glob pattern.
     *
     * <p>The pattern uses glob syntax (e.g. {@code "echo:*"}, {@code "user:*:props"}).
     * All implementations must support glob patterns.</p>
     *
     * @param pattern a glob pattern
     * @return a future that completes with the set of matching keys
     */
    @NotNull CompletableFuture<@NotNull Set<String>> getKeys(final @NotNull String pattern);

    /**
     * Acquires a distributed lock, executes the action, then releases it.
     *
     * <p>The lock is acquired with the given wait and lease times. If acquired, the
     * supplier's future is awaited, then the lock is released. If the lock cannot be
     * acquired within {@code waitTime}, the action is not executed.</p>
     *
     * @param key       the lock key
     * @param waitTime  maximum time to wait for the lock
     * @param leaseTime maximum time to hold the lock
     * @param unit      the time unit for waitTime and leaseTime
     * @param action    the action to execute while holding the lock
     * @return a future that completes with {@code true} if the lock was acquired and
     *         the action executed, {@code false} if the lock could not be acquired
     */
    @NotNull CompletableFuture<Boolean> withLock(final @NotNull String key,
                                                  long waitTime,
                                                  long leaseTime,
                                                  final @NotNull TimeUnit unit,
                                                  final @NotNull Supplier<@NotNull CompletableFuture<Void>> action);

}
