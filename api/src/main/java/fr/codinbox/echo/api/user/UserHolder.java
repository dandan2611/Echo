package fr.codinbox.echo.api.user;

import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;

public interface UserHolder {

    @NotNull EchoFuture<@NotNull Map<UUID, Long>> getConnectedUsers();

    @NotNull EchoFuture<@NotNull Boolean> hasUser(final @NotNull UUID id);

    /**
     * Register a {@link User} to this {@link UserHolder}.
     * <br>
     * This method <b>does not</b> unregister the user from other resources of the same type.
     *
     * @param user the user to register
     * @return {@code true} if the user was successfully registered, {@code false} otherwise
     */
    @ApiStatus.Internal
    @NotNull EchoFuture<@NotNull Boolean> registerUser(final @NotNull User user);

    @ApiStatus.Internal
    @NotNull EchoFuture<@NotNull Boolean> unregisterUser(final @NotNull User user);

    @ApiStatus.Internal
    @NotNull EchoFuture<@NotNull Boolean> clearUsers();

}
