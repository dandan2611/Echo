package fr.codinbox.echo.api.utils;

import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.NotNull;

/**
 * A resource that supports graceful cleanup, releasing all associated data without leaving
 * orphaned state behind.
 *
 * <p>Implemented by {@link fr.codinbox.echo.api.user.User User},
 * {@link fr.codinbox.echo.api.server.Server Server}, and
 * {@link fr.codinbox.echo.api.proxy.Proxy Proxy}. Cleanup removes the resource's properties,
 * address, user registrations, and other associated data from Redis.</p>
 */
public interface Cleanable {

    /**
     * Cleans up all data associated with this resource.
     *
     * <p>After cleanup, the resource is no longer usable and should be discarded.</p>
     *
     * @return a future that completes when the cleanup is finished
     */
    @NotNull EchoFuture<Void> cleanup();

}
