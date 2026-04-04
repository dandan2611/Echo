package fr.codinbox.echo.api.exception.resource;

import fr.codinbox.echo.api.exception.UnknownResourceException;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a proxy with the specified identifier cannot be found in the network.
 *
 * <pre>{@code
 * Proxy proxy = client.getProxyById("proxy-eu").await()
 *     .orElseThrow(() -> new UnknownProxyException("proxy-eu"));
 * }</pre>
 *
 * @see fr.codinbox.echo.api.EchoClient#getProxyById(String)
 */
public class UnknownProxyException extends UnknownResourceException {

    /**
     * Creates a new exception for an unknown proxy.
     *
     * @param name the proxy identifier that was not found
     */
    public UnknownProxyException(final @NotNull String name) {
        super("proxy", name);
    }

    /**
     * Creates a new exception for an unknown proxy with no identifier.
     */
    public UnknownProxyException() {
        super("proxy", "");
    }

}
