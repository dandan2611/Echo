package fr.codinbox.echo.api.cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * An async-first distributed map abstraction backed by the cache provider.
 *
 * <p>Instances are lightweight handles — safe to create repeatedly via
 * {@link CacheProvider#getMap(String)}. Thread safety is inherited from the
 * backend implementation.</p>
 *
 * @param <K> the map key type
 * @param <V> the map value type
 * @see CacheProvider#getMap(String)
 */
public interface CacheMap<K, V> {

    /**
     * Gets all entries in the map.
     *
     * @return a future that completes with all key-value pairs
     */
    @NotNull CompletableFuture<@NotNull Map<K, V>> readAllAsync();

    /**
     * Gets a single value by key.
     *
     * @param key the key to look up
     * @return a future that completes with the value, or {@code null} if the key does not exist
     */
    @NotNull CompletableFuture<@Nullable V> getAsync(final @NotNull K key);

    /**
     * Puts a key-value pair, returning the previous value.
     *
     * @param key   the key
     * @param value the value
     * @return a future that completes with the previous value, or {@code null} if there was none
     */
    @NotNull CompletableFuture<@Nullable V> putAsync(final @NotNull K key, final @NotNull V value);

    /**
     * Puts a key-value pair without fetching the previous value.
     *
     * @param key   the key
     * @param value the value
     * @return a future that completes with {@code true} if the key was newly inserted
     */
    @NotNull CompletableFuture<@NotNull Boolean> fastPutAsync(final @NotNull K key, final @NotNull V value);

    /**
     * Removes a key, returning the previous value.
     *
     * @param key the key to remove
     * @return a future that completes with the previous value, or {@code null} if there was none
     */
    @NotNull CompletableFuture<@Nullable V> removeAsync(final @NotNull K key);

    /**
     * Removes one or more keys without fetching previous values.
     *
     * @param keys the keys to remove
     * @return a future that completes with the number of keys actually removed
     */
    @SuppressWarnings("unchecked")
    @NotNull CompletableFuture<@NotNull Long> fastRemoveAsync(final @NotNull K... keys);

    /**
     * Checks whether a key exists in the map.
     *
     * @param key the key to check
     * @return a future that completes with {@code true} if the key exists
     */
    @NotNull CompletableFuture<@NotNull Boolean> containsKeyAsync(final @NotNull K key);

    /**
     * Removes all entries from the map.
     *
     * @return a future that completes when the map is cleared
     */
    @NotNull CompletableFuture<Void> clearAsync();

    /**
     * Gets the number of entries in the map.
     *
     * @return a future that completes with the entry count
     */
    @NotNull CompletableFuture<@NotNull Long> sizeAsync();

    /**
     * Gets all keys in the map.
     *
     * @return a future that completes with the set of keys
     */
    @NotNull CompletableFuture<@NotNull Set<K>> keysAsync();

}
