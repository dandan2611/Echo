package fr.codinbox.echo.api.exception.user;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Thrown when an operation requires the user to be connected to a proxy, but they are not.
 *
 * <p>This typically occurs when attempting to transfer a player to a server or proxy,
 * but the player has already disconnected from the network.</p>
 *
 * @see fr.codinbox.echo.api.user.User#tryConnectToServer(String)
 * @see fr.codinbox.echo.api.user.User#tryConnectToProxy(fr.codinbox.echo.api.proxy.Proxy)
 */
public class UserHasNoProxyException extends RuntimeException {

    /**
     * Creates a new exception for a user with no proxy connection.
     *
     * @param userId the UUID of the user who has no proxy
     */
    public UserHasNoProxyException(final @NotNull UUID userId) {
        super("User with id '" + userId + "' has no proxy, it could be caused by a disconnection from the network");
    }

}
