package fr.codinbox.echo.api.user;

import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * A resource that can hold (track) connected users.
 *
 * <p>Both {@link fr.codinbox.echo.api.server.Server Server} and
 * {@link fr.codinbox.echo.api.proxy.Proxy Proxy} implement this interface to track
 * which players are connected to them.</p>
 *
 * <pre>{@code
 * // Get all players on a server
 * Map<UUID, Long> players = server.getConnectedUsers().await();
 * System.out.println("Players online: " + players.size());
 *
 * // Check if a specific player is on this server
 * boolean isHere = server.hasUser(playerUuid).await();
 * }</pre>
 */
public interface UserHolder {

    /**
     * Gets all users currently connected to this resource.
     *
     * <p>Returns a map where keys are player UUIDs and values are their join timestamps
     * (milliseconds since epoch).</p>
     *
     * <pre>{@code
     * Map<UUID, Long> users = server.getConnectedUsers().await();
     * users.forEach((uuid, joinedAt) ->
     *     System.out.println(uuid + " joined at " + Instant.ofEpochMilli(joinedAt))
     * );
     * }</pre>
     *
     * @return a future that completes with a map of user UUIDs to join timestamps
     */
    @NotNull EchoFuture<@NotNull Map<UUID, Long>> getConnectedUsers();

    /**
     * Checks whether a user is currently connected to this resource.
     *
     * <pre>{@code
     * boolean online = server.hasUser(playerUuid).await();
     * }</pre>
     *
     * @param id the user's UUID
     * @return a future that completes with {@code true} if the user is connected
     */
    @NotNull EchoFuture<@NotNull Boolean> hasUser(final @NotNull UUID id);

    /**
     * Registers a user as connected to this resource.
     *
     * <p><b>Internal use only.</b> This method does <b>not</b> unregister the user from
     * other resources of the same type. Use
     * {@link fr.codinbox.echo.api.EchoClient#registerUserInServer(User, fr.codinbox.echo.api.server.Server)}
     * for safe server registration.</p>
     *
     * @param user the user to register
     * @return a future that completes with {@code true} if the user was successfully registered
     */
    @ApiStatus.Internal
    @NotNull EchoFuture<@NotNull Boolean> registerUser(final @NotNull User user);

    /**
     * Unregisters a user from this resource.
     *
     * <p><b>Internal use only.</b> Called automatically when a player disconnects
     * or switches to another resource.</p>
     *
     * @param user the user to unregister
     * @return a future that completes with {@code true} if the user was successfully unregistered
     */
    @ApiStatus.Internal
    @NotNull EchoFuture<@NotNull Boolean> unregisterUser(final @NotNull User user);

    /**
     * Removes all users from this resource.
     *
     * <p><b>Internal use only.</b> Used during resource cleanup (e.g. when a server shuts down).</p>
     *
     * @return a future that completes with {@code true} if the operation succeeded
     */
    @ApiStatus.Internal
    @NotNull EchoFuture<@NotNull Boolean> clearUsers();

}
