package fr.codinbox.echo.ondemand;

import fr.codinbox.echo.ondemand.internal.OnDemandServersRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Acquires and terminates servers without exposing the underlying orchestrator.
 */
public interface OnDemandServers {

    /**
     * Returns the on-demand server adapter loaded in this process.
     *
     * @return the loaded adapter
     * @throws IllegalStateException if no adapter is loaded
     */
    static @NotNull OnDemandServers load() {
        return OnDemandServersRegistry.load();
    }

    /**
     * Acquires one server and applies its requested Echo properties before completion.
     * Reusing a request ID must return the same live allocation.
     *
     * @param request requested server type and idempotency key
     * @return the acquired Echo server
     */
    @NotNull CompletableFuture<@NotNull ServerHandle> acquire(final @NotNull ServerRequest request);

    /**
     * Terminates an acquired server. Calling this more than once must be safe.
     *
     * @param server server to terminate
     * @return completion of termination
     */
    @NotNull CompletableFuture<Void> terminate(final @NotNull ServerHandle server);

    /**
     * Returns administrative controls when supported by this implementation.
     *
     * @return the on-demand administration API
     * @throws UnsupportedOperationException if this implementation does not expose administration
     */
    default @NotNull OnDemandAdministration administration() {
        throw new UnsupportedOperationException("On-demand administration is not supported");
    }
}
