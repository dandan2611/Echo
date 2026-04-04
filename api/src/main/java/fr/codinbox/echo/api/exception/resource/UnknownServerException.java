package fr.codinbox.echo.api.exception.resource;

import fr.codinbox.echo.api.exception.UnknownResourceException;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a server with the specified identifier cannot be found in the network.
 *
 * <pre>{@code
 * Server server = client.getServerById("lobby-1").await()
 *     .orElseThrow(() -> new UnknownServerException("lobby-1"));
 * }</pre>
 *
 * @see fr.codinbox.echo.api.EchoClient#getServerById(String)
 */
public class UnknownServerException extends UnknownResourceException {

    /**
     * Creates a new exception for an unknown server.
     *
     * @param name the server identifier that was not found
     */
    public UnknownServerException(final @NotNull String name) {
        super("server", name);
    }

}
