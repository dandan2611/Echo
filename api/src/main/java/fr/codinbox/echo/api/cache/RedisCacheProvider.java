package fr.codinbox.echo.api.cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RBatch;
import org.redisson.api.RMap;
import org.redisson.api.RMapAsync;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Low-level interface for direct Redis operations via Redisson.
 *
 * <p><b>Warning:</b> This provides raw access to the Redis store that backs the Echo network.
 * Incorrect usage can corrupt network state. Prefer the higher-level APIs
 * ({@link fr.codinbox.echo.api.property.PropertyHolder PropertyHolder},
 * {@link fr.codinbox.echo.api.messaging.MessagingProvider MessagingProvider}) whenever possible.</p>
 *
 * <p>Access via {@link fr.codinbox.echo.api.EchoClient#getCacheProvider()}:</p>
 *
 * <pre>{@code
 * RedisCacheProvider cache = client.getCacheProvider();
 *
 * // Store and retrieve a value
 * cache.setObject("my:key", "hello").join();
 * String value = cache.<String>getObject("my:key").join();
 *
 * // Set TTL
 * cache.expireObject("my:key", Instant.now().plusSeconds(3600)).join();
 *
 * // Check existence
 * boolean exists = cache.hasObject("my:key").join();
 *
 * // Delete
 * cache.deleteObject("my:key").join();
 * }</pre>
 *
 * @see fr.codinbox.echo.api.EchoClient#getCacheProvider()
 */
public interface RedisCacheProvider {

    /**
     * Gets an object from Redis by its key.
     *
     * <pre>{@code
     * String value = cache.<String>getObject("my:key").join();
     * }</pre>
     *
     * @param key the Redis key
     * @param <T> the expected value type
     * @return a future that completes with the value, or {@code null} if the key does not exist
     */
    <T> @NotNull CompletableFuture<@Nullable T> getObject(final @NotNull String key);

    /**
     * Gets an object from Redis synchronously (blocking).
     *
     * <p>Convenience wrapper around {@link #getObject(String)} that blocks until the result
     * is available.</p>
     *
     * <pre>{@code
     * String value = cache.<String>getObjectSync("my:key");
     * }</pre>
     *
     * @param key the Redis key
     * @param <T> the expected value type
     * @return the value, or {@code null} if the key does not exist
     */
    default <T> @Nullable T getObjectSync(final @NotNull String key) {
        final CompletableFuture<T> object = this.getObject(key);
        return object.join();
    }

    /**
     * Stores an object in Redis under the given key.
     *
     * <pre>{@code
     * cache.setObject("my:key", "hello").join();
     * cache.setObject("my:counter", 42).join();
     * }</pre>
     *
     * @param key   the Redis key
     * @param value the value to store
     * @param <T>   the value type
     * @return a future that completes when the value is stored
     */
    @NotNull <T> CompletableFuture<Void> setObject(final @NotNull String key, final @NotNull T value);

    /**
     * Sets an expiration time on a Redis key.
     *
     * <p>After the specified instant, the key will be automatically deleted by Redis.</p>
     *
     * <pre>{@code
     * // Expire in 1 hour
     * cache.expireObject("my:key", Instant.now().plusSeconds(3600)).join();
     * }</pre>
     *
     * @param key     the Redis key
     * @param instant the time at which the key should expire
     * @return a future that completes with {@code true} if the TTL was set
     */
    @NotNull CompletableFuture<Boolean> expireObject(final @NotNull String key, final @NotNull Instant instant);

    /**
     * Deletes a key from Redis.
     *
     * <pre>{@code
     * boolean deleted = cache.deleteObject("my:key").join();
     * }</pre>
     *
     * @param key the Redis key to delete
     * @return a future that completes with {@code true} if the key was deleted
     */
    @NotNull CompletableFuture<Boolean> deleteObject(final @NotNull String key);

    /**
     * Gets the remaining time-to-live of a Redis key in milliseconds.
     *
     * <p>Returns {@code -1} if the key has no TTL, or {@code -2} if the key does not exist.</p>
     *
     * <pre>{@code
     * long ttl = cache.getObjectRemainingTimeToLive("my:key").join();
     * if (ttl > 0) {
     *     System.out.println("Expires in " + ttl + "ms");
     * }
     * }</pre>
     *
     * @param key the Redis key
     * @return a future that completes with the remaining TTL in milliseconds
     */
    @NotNull CompletableFuture<Long> getObjectRemainingTimeToLive(final @NotNull String key);

    /**
     * Checks whether a key exists in Redis.
     *
     * <pre>{@code
     * boolean exists = cache.hasObject("my:key").join();
     * }</pre>
     *
     * @param key the Redis key
     * @return a future that completes with {@code true} if the key exists
     */
    @NotNull CompletableFuture<Boolean> hasObject(final @NotNull String key);

    /**
     * Gets a Redisson {@link RMap} (synchronous map) backed by a Redis hash.
     *
     * <pre>{@code
     * RMap<String, String> map = cache.getMap("my:hash");
     * map.put("field", "value");
     * String value = map.get("field");
     * }</pre>
     *
     * @param key the Redis key for the hash
     * @param <K> the map key type
     * @param <V> the map value type
     * @return the Redisson map
     */
    <K, V> @NotNull RMap<K, V> getMap(final @NotNull String key);

    /**
     * Gets a Redisson {@link RMapAsync} (asynchronous map) backed by a Redis hash.
     *
     * <pre>{@code
     * RMapAsync<String, String> map = cache.getAsyncMap("my:hash");
     * map.putAsync("field", "value").join();
     * }</pre>
     *
     * @param key the Redis key for the hash
     * @param <K> the map key type
     * @param <V> the map value type
     * @return the async Redisson map
     */
    <K, V> @NotNull RMapAsync<K, V> getAsyncMap(final @NotNull String key);

    /**
     * Finds all Redis keys matching a glob pattern.
     *
     * <pre>{@code
     * Set<String> keys = cache.getKeys("echo:server:*").join();
     * }</pre>
     *
     * @param pattern a Redis glob pattern (e.g. {@code "echo:*"}, {@code "user:*:props"})
     * @return a future that completes with the set of matching keys
     */
    @NotNull CompletableFuture<@NotNull Set<String>> getKeys(final @NotNull String pattern);

    /**
     * Executes multiple Redis operations in a single batch (pipeline).
     *
     * <p>Batching reduces round-trips to Redis and improves performance when executing
     * multiple commands together.</p>
     *
     * <pre>{@code
     * cache.executeBatch(batch -> {
     *     batch.getBucket("key1").setAsync("value1");
     *     batch.getBucket("key2").setAsync("value2");
     *     batch.getBucket("key3").deleteAsync();
     * }).join();
     * }</pre>
     *
     * @param operations a consumer that adds operations to the Redisson {@link RBatch}
     * @return a future that completes when the batch has been executed
     */
    @NotNull CompletableFuture<Void> executeBatch(final @NotNull Consumer<RBatch> operations);

}
