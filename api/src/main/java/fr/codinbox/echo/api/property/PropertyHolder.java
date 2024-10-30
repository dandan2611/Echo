package fr.codinbox.echo.api.property;

import org.jetbrains.annotations.CheckReturnValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface PropertyHolder {

    @CheckReturnValue
    @NotNull CompletableFuture<Boolean> hasProperty(final @NotNull String key);

    @CheckReturnValue
    @NotNull CompletableFuture<Long> getPropertyTimeToLive(final @NotNull String key);

    @NotNull <T> CompletableFuture<Void> setProperty(final @NotNull String key,
                                                 final @Nullable T value);

    @NotNull CompletableFuture<Boolean> deleteProperty(final @NotNull String key);

    @CheckReturnValue
    @NotNull <T> CompletableFuture<@Nullable T> getProperty(final @NotNull String key);

    @NotNull CompletableFuture<Boolean> setExpire(final @NotNull String key,
                                                  final @NotNull Instant instant);

    @NotNull CompletableFuture<@NotNull Set<String>> getProperties();

}
