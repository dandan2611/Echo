package fr.codinbox.echo.api.user;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface UserHolder {

    @NotNull CompletableFuture<@NotNull Map<UUID, Long>> getConnectedUsersAsync();

    @Blocking
    default @NotNull Map<UUID, Long> getConnectedUsers() {
        return this.getConnectedUsersAsync().join();
    }

    @NotNull CompletableFuture<@NotNull Boolean> hasUserAsync(final @NotNull UUID id);

    @Blocking
    default boolean hasUser(final @NotNull UUID id) {
        return this.hasUserAsync(id).join();
    }

    /**
     * Register a {@link User} to this {@link UserHolder}.
     * <br>
     * This method <b>does not</b> unregister the user from other resources of the same type.
     *
     * @param user the user to register
     * @return {@code true} if the user was successfully registered, {@code false} otherwise
     */
    @ApiStatus.Internal
    @NotNull CompletableFuture<@NotNull Boolean> registerUserAsync(final @NotNull User user);

    @ApiStatus.Internal
    @Blocking
    default boolean registerUser(final @NotNull User user) {
        return this.registerUserAsync(user).join();
    }

    @ApiStatus.Internal
    @NotNull CompletableFuture<@NotNull Boolean> unregisterUserAsync(final @NotNull User user);

    @ApiStatus.Internal
    @Blocking
    default boolean unregisterUser(final @NotNull User user) {
        return this.unregisterUserAsync(user).join();
    }

    @ApiStatus.Internal
    @NotNull CompletableFuture<@NotNull Boolean> clearUsersAsync();

    @ApiStatus.Internal
    @Blocking
    default boolean clearUsers() {
        return this.clearUsersAsync().join();
    }

}
