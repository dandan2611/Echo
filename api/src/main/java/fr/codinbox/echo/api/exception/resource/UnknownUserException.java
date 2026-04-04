package fr.codinbox.echo.api.exception.resource;

import fr.codinbox.echo.api.exception.UnknownResourceException;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a user with the specified identifier cannot be found in the network.
 *
 * <pre>{@code
 * User user = client.getUserById(uuid).await()
 *     .orElseThrow(() -> new UnknownUserException(uuid.toString()));
 * }</pre>
 *
 * @see fr.codinbox.echo.api.EchoClient#getUserById(java.util.UUID)
 * @see fr.codinbox.echo.api.EchoClient#getUserByUsername(String)
 */
public class UnknownUserException extends UnknownResourceException {

    /**
     * Creates a new exception for an unknown user.
     *
     * @param name the user identifier (UUID string) that was not found
     */
    public UnknownUserException(final @NotNull String name) {
        super("user", name);
    }

}
