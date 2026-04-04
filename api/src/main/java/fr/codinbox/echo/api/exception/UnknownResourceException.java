package fr.codinbox.echo.api.exception;

import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a requested resource (user, server, or proxy) cannot be found in the network.
 *
 * <p>This is the base class for resource-specific exceptions:</p>
 * <ul>
 *     <li>{@link fr.codinbox.echo.api.exception.resource.UnknownUserException} - user not found</li>
 *     <li>{@link fr.codinbox.echo.api.exception.resource.UnknownServerException} - server not found</li>
 *     <li>{@link fr.codinbox.echo.api.exception.resource.UnknownProxyException} - proxy not found</li>
 * </ul>
 *
 * @see fr.codinbox.echo.api.exception.resource.UnknownUserException
 * @see fr.codinbox.echo.api.exception.resource.UnknownServerException
 * @see fr.codinbox.echo.api.exception.resource.UnknownProxyException
 */
public class UnknownResourceException extends RuntimeException {

    /**
     * Creates a new exception for an unknown resource.
     *
     * @param resourceType the type of resource (e.g. {@code "user"}, {@code "server"}, {@code "proxy"})
     * @param name         the identifier of the resource that was not found
     */
    public UnknownResourceException(final @NotNull String resourceType, final @NotNull String name) {
        super("Resource '" + resourceType + "' with name '" + name + "' has not been found");
    }

}
