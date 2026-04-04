package fr.codinbox.echo.api.server;

import org.jetbrains.annotations.NotNull;

/**
 * A network resource that can be connected to via a network address.
 *
 * <p>Both {@link Server} and {@link fr.codinbox.echo.api.proxy.Proxy Proxy} implement this
 * interface, as they both expose a host and port that clients can connect to.</p>
 *
 * <pre>{@code
 * Address addr = server.getAddress();
 * InetSocketAddress socket = addr.toInetSocketAddress();
 * }</pre>
 *
 * @see Address
 */
public interface Joinable {

    /**
     * Gets the network address of this resource.
     *
     * @return the address (host and port) of this resource
     */
    @NotNull Address getAddress();

}
