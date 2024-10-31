package fr.codinbox.echo.api.property;

import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.CheckReturnValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface PropertyHolder {

    @CheckReturnValue
    @NotNull CompletableFuture<@NotNull Boolean> hasPropertyAsync(final @NotNull String key);

    @Blocking
    default boolean hasProperty(final @NotNull String key) {
        return this.hasPropertyAsync(key).join();
    }

    default @NotNull CompletableFuture<@NotNull Boolean> hasPropertyAsync(final @NotNull PropertyKey<?> key) {
        return this.hasPropertyAsync(key.key());
    }

    @Blocking
    default boolean hasProperty(final @NotNull PropertyKey<?> key) {
        return this.hasPropertyAsync(key).join();
    }

    @CheckReturnValue
    @NotNull CompletableFuture<@NotNull Long> getPropertyTimeToLiveAsync(final @NotNull String key);

    @Blocking
    default long getPropertyTimeToLive(final @NotNull String key) {
        return this.getPropertyTimeToLiveAsync(key).join();
    }

    default @NotNull CompletableFuture<@NotNull Long> getPropertyTimeToLiveAsync(final @NotNull PropertyKey<?> key) {
        return this.getPropertyTimeToLiveAsync(key.key());
    }

    @Blocking
    default long getPropertyTimeToLive(final @NotNull PropertyKey<?> key) {
        return this.getPropertyTimeToLiveAsync(key).join();
    }

    @NotNull <T> CompletableFuture<Void> setPropertyAsync(final @NotNull String key,
                                                          final @Nullable T value);

    @Blocking
    default <T> void setProperty(final @NotNull String key, final @Nullable T value) {
        this.setPropertyAsync(key, value).join();
    }

    default @NotNull <T> CompletableFuture<Void> setPropertyAsync(final @NotNull PropertyKey<T> key,
                                                                  final @Nullable T value) {
        return this.setPropertyAsync(key.key(), value);
    }

    @Blocking
    default <T> void setProperty(final @NotNull PropertyKey<T> key, final @Nullable T value) {
        this.setPropertyAsync(key, value).join();
    }

    @NotNull CompletableFuture<@NotNull Boolean> deletePropertyAsync(final @NotNull String key);

    @Blocking
    default boolean deleteProperty(final @NotNull String key) {
        return this.deletePropertyAsync(key).join();
    }

    default @NotNull CompletableFuture<@NotNull Boolean> deletePropertyAsync(final @NotNull PropertyKey<?> key) {
        return this.deletePropertyAsync(key.key());
    }

    @Blocking
    default boolean deleteProperty(final @NotNull PropertyKey<?> key) {
        return this.deletePropertyAsync(key).join();
    }

    @NotNull <T> CompletableFuture<@NotNull Optional<T>> getPropertyAsync(final @NotNull String key);

    @Blocking
    default <T> @NotNull Optional<T> getProperty(final @NotNull String key) {
        final CompletableFuture<Optional<T>> cf = this.getPropertyAsync(key);
        return cf.join();
    }

    default @NotNull <T> CompletableFuture<@NotNull Optional<T>> getPropertyAsync(final @NotNull PropertyKey<T> key) {
        return this.getPropertyAsync(key.key());
    }

    @Blocking
    default <T> @NotNull Optional<T> getProperty(final @NotNull PropertyKey<T> key) {
        return this.getPropertyAsync(key).join();
    }

    @NotNull CompletableFuture<Boolean> setExpireAsync(final @NotNull String key,
                                                       final @NotNull Instant instant);

    @Blocking
    default boolean setExpire(final @NotNull String key, final @NotNull Instant instant) {
        return this.setExpireAsync(key, instant).join();
    }

    default @NotNull CompletableFuture<Boolean> setExpireAsync(final @NotNull PropertyKey<?> key,
                                                              final @NotNull Instant instant) {
        return this.setExpireAsync(key.key(), instant);
    }

    @Blocking
    default boolean setExpire(final @NotNull PropertyKey<?> key, final @NotNull Instant instant) {
        return this.setExpireAsync(key, instant).join();
    }

    @NotNull CompletableFuture<@NotNull Set<String>> getPropertiesKeysAsync();

    @Blocking
    default @NotNull Set<String> getPropertiesKeys() {
        return this.getPropertiesKeysAsync().join();
    }

}
