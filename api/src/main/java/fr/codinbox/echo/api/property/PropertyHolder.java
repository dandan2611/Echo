package fr.codinbox.echo.api.property;

import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.CheckReturnValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * A resource that supports type-safe key-value property storage backed by Redis.
 *
 * <p>Users, servers, and proxies all implement this interface. Properties are stored
 * in Redis and accessible from any node in the network. They support optional TTL
 * (time-to-live) for automatic expiration.</p>
 *
 * <pre>{@code
 * // Define typed keys
 * PropertyKey<Integer> LEVEL = new PropertyKey<>("level");
 * PropertyKey<String> RANK = new PropertyKey<>("rank");
 *
 * // Set properties
 * user.setProperty(LEVEL, 42).await();
 * user.setProperty(RANK, "admin").await();
 *
 * // Get properties
 * Optional<Integer> level = user.getProperty(LEVEL).await(); // Optional[42]
 * Optional<String> rank = user.getProperty(RANK).await();    // Optional["admin"]
 *
 * // Set TTL (auto-expire after 1 hour)
 * user.setExpire(LEVEL, Instant.now().plusSeconds(3600)).await();
 *
 * // Check existence and delete
 * boolean exists = user.hasProperty(LEVEL).await(); // true
 * user.deleteProperty(LEVEL).await();
 *
 * // List all property keys
 * Set<String> keys = user.getPropertiesKeys().await();
 * }</pre>
 *
 * @see PropertyKey
 */
public interface PropertyHolder {

    /**
     * Checks whether a property with the given key exists on this resource.
     *
     * @param key the property key string
     * @return a future that completes with {@code true} if the property exists
     */
    @CheckReturnValue
    @NotNull EchoFuture<@NotNull Boolean> hasProperty(final @NotNull String key);

    /**
     * Checks whether a property with the given key exists on this resource.
     *
     * @param key the typed property key
     * @return a future that completes with {@code true} if the property exists
     */
    default @NotNull EchoFuture<@NotNull Boolean> hasProperty(final @NotNull PropertyKey<?> key) {
        return this.hasProperty(key.key());
    }

    /**
     * Gets the remaining time-to-live of a property in milliseconds.
     *
     * <p>Returns {@code -1} if the property has no TTL set, or {@code -2} if the property
     * does not exist.</p>
     *
     * <pre>{@code
     * long ttl = user.getPropertyTimeToLive(LEVEL).await();
     * if (ttl > 0) {
     *     System.out.println("Expires in " + ttl + "ms");
     * }
     * }</pre>
     *
     * @param key the property key string
     * @return a future that completes with the remaining TTL in milliseconds
     */
    @CheckReturnValue
    @NotNull EchoFuture<@NotNull Long> getPropertyTimeToLive(final @NotNull String key);

    /**
     * Gets the remaining time-to-live of a property in milliseconds.
     *
     * @param key the typed property key
     * @return a future that completes with the remaining TTL in milliseconds
     * @see #getPropertyTimeToLive(String)
     */
    default @NotNull EchoFuture<@NotNull Long> getPropertyTimeToLive(final @NotNull PropertyKey<?> key) {
        return this.getPropertyTimeToLive(key.key());
    }

    /**
     * Sets a property value on this resource.
     *
     * <p>The value is serialized and stored in Redis. Pass {@code null} to remove the property.</p>
     *
     * <pre>{@code
     * user.setProperty("level", 42).await();
     * server.setProperty("motd", "Welcome!").await();
     * }</pre>
     *
     * @param key   the property key string
     * @param value the value to store (may be {@code null} to remove)
     * @param <T>   the value type
     * @return a future that completes when the property is set
     */
    @NotNull <T> EchoFuture<Void> setProperty(final @NotNull String key,
                                               final @Nullable T value);

    /**
     * Sets a property value on this resource using a typed key.
     *
     * <pre>{@code
     * PropertyKey<Integer> LEVEL = new PropertyKey<>("level");
     * user.setProperty(LEVEL, 42).await();
     * }</pre>
     *
     * @param key   the typed property key
     * @param value the value to store (may be {@code null} to remove)
     * @param <T>   the value type
     * @return a future that completes when the property is set
     */
    default @NotNull <T> EchoFuture<Void> setProperty(final @NotNull PropertyKey<T> key,
                                                      final @Nullable T value) {
        return this.setProperty(key.key(), value);
    }

    /**
     * Deletes a property from this resource.
     *
     * @param key the property key string
     * @return a future that completes with {@code true} if the property was deleted
     */
    @NotNull EchoFuture<@NotNull Boolean> deleteProperty(final @NotNull String key);

    /**
     * Deletes a property from this resource.
     *
     * @param key the typed property key
     * @return a future that completes with {@code true} if the property was deleted
     */
    default @NotNull EchoFuture<@NotNull Boolean> deleteProperty(final @NotNull PropertyKey<?> key) {
        return this.deleteProperty(key.key());
    }

    /**
     * Gets a property value from this resource.
     *
     * <pre>{@code
     * Optional<Integer> level = user.<Integer>getProperty("level").await();
     * }</pre>
     *
     * @param key the property key string
     * @param <T> the expected value type
     * @return a future that completes with the value, or empty if the property does not exist
     */
    @NotNull <T> EchoFuture<@NotNull Optional<T>> getProperty(final @NotNull String key);

    /**
     * Gets a property value from this resource using a typed key.
     *
     * <pre>{@code
     * PropertyKey<Integer> LEVEL = new PropertyKey<>("level");
     * Optional<Integer> level = user.getProperty(LEVEL).await();
     * }</pre>
     *
     * @param key the typed property key
     * @param <T> the value type
     * @return a future that completes with the value, or empty if the property does not exist
     */
    default @NotNull <T> EchoFuture<@NotNull Optional<T>> getProperty(final @NotNull PropertyKey<T> key) {
        return this.getProperty(key.key());
    }

    /**
     * Sets an expiration time (TTL) on a property.
     *
     * <p>After the specified instant, the property will be automatically deleted from Redis.</p>
     *
     * <pre>{@code
     * // Expire in 1 hour
     * user.setExpire(LEVEL, Instant.now().plusSeconds(3600)).await();
     *
     * // Expire at a specific time
     * user.setExpire(RANK, Instant.parse("2025-01-01T00:00:00Z")).await();
     * }</pre>
     *
     * @param key     the property key string
     * @param instant the time at which the property should expire
     * @return a future that completes with {@code true} if the TTL was set
     */
    @NotNull EchoFuture<Boolean> setExpire(final @NotNull String key,
                                           final @NotNull Instant instant);

    /**
     * Sets an expiration time (TTL) on a property using a typed key.
     *
     * @param key     the typed property key
     * @param instant the time at which the property should expire
     * @return a future that completes with {@code true} if the TTL was set
     * @see #setExpire(String, Instant)
     */
    default @NotNull EchoFuture<Boolean> setExpire(final @NotNull PropertyKey<?> key,
                                                   final @NotNull Instant instant) {
        return this.setExpire(key.key(), instant);
    }

    /**
     * Gets all property key names currently set on this resource.
     *
     * <pre>{@code
     * Set<String> keys = user.getPropertiesKeys().await();
     * // e.g. ["username", "level", "rank", "current_server_id"]
     * }</pre>
     *
     * @return a future that completes with the set of property key strings
     */
    @NotNull EchoFuture<@NotNull Set<String>> getPropertiesKeys();

}
