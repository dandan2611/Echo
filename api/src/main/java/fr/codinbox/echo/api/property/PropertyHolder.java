package fr.codinbox.echo.api.property;

import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.CheckReturnValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public interface PropertyHolder {

    @CheckReturnValue
    @NotNull EchoFuture<@NotNull Boolean> hasProperty(final @NotNull String key);

    default @NotNull EchoFuture<@NotNull Boolean> hasProperty(final @NotNull PropertyKey<?> key) {
        return this.hasProperty(key.key());
    }

    @CheckReturnValue
    @NotNull EchoFuture<@NotNull Long> getPropertyTimeToLive(final @NotNull String key);

    default @NotNull EchoFuture<@NotNull Long> getPropertyTimeToLive(final @NotNull PropertyKey<?> key) {
        return this.getPropertyTimeToLive(key.key());
    }

    @NotNull <T> EchoFuture<Void> setProperty(final @NotNull String key,
                                               final @Nullable T value);

    default @NotNull <T> EchoFuture<Void> setProperty(final @NotNull PropertyKey<T> key,
                                                      final @Nullable T value) {
        return this.setProperty(key.key(), value);
    }

    @NotNull EchoFuture<@NotNull Boolean> deleteProperty(final @NotNull String key);

    default @NotNull EchoFuture<@NotNull Boolean> deleteProperty(final @NotNull PropertyKey<?> key) {
        return this.deleteProperty(key.key());
    }

    @NotNull <T> EchoFuture<@NotNull Optional<T>> getProperty(final @NotNull String key);

    default @NotNull <T> EchoFuture<@NotNull Optional<T>> getProperty(final @NotNull PropertyKey<T> key) {
        return this.getProperty(key.key());
    }

    @NotNull EchoFuture<Boolean> setExpire(final @NotNull String key,
                                           final @NotNull Instant instant);

    default @NotNull EchoFuture<Boolean> setExpire(final @NotNull PropertyKey<?> key,
                                                   final @NotNull Instant instant) {
        return this.setExpire(key.key(), instant);
    }

    @NotNull EchoFuture<@NotNull Set<String>> getPropertiesKeys();

}
