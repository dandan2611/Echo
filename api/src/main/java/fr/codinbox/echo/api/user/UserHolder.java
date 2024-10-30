package fr.codinbox.echo.api.user;

import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.CheckReturnValue;
import org.jetbrains.annotations.NonBlocking;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface UserHolder {

    @CheckReturnValue
    @NotNull CompletableFuture<@NotNull Map<UUID, Instant>> getConnectedUsersAsync();

    @CheckReturnValue
    @Blocking
    default @NotNull Map<UUID, Instant> getConnectedUsers() {
        return this.getConnectedUsersAsync().join();
    }

    @CheckReturnValue
    @NotNull CompletableFuture<@NotNull Boolean> hasUserAsync(final @NotNull UUID id);

    @CheckReturnValue
    @Blocking
    default boolean hasUser(final @NotNull UUID id) {
        return this.hasUserAsync(id).join();
    }

    @NotNull CompletableFuture<@NotNull Boolean> registerUserAsync(final @NotNull User user);

    @Blocking
    default boolean registerUser(final @NotNull User user) {
        return this.registerUserAsync(user).join();
    }

    @NotNull CompletableFuture<@NotNull Boolean> unregisterUserAsync(final @NotNull User user);

    @Blocking
    default boolean unregisterUser(final @NotNull User user) {
        return this.unregisterUserAsync(user).join();
    }

}
