package fr.codinbox.echo.api.proxy;

import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.id.Identifiable;
import fr.codinbox.echo.api.messaging.MessageRouter;
import fr.codinbox.echo.api.property.PropertyHolder;
import fr.codinbox.echo.api.server.Joinable;
import fr.codinbox.echo.api.user.UserHolder;
import fr.codinbox.echo.api.utils.Cleanable;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a proxy (e.g. a Velocity instance) in the Echo network.
 *
 * <p>A proxy is identified by a unique string ID (e.g. {@code "proxy-eu"}, {@code "proxy-us"})
 * and provides access to its connected users, custom properties, network address, and messaging.</p>
 *
 * <p>Proxies handle player connections and routing between backend servers. They always
 * perform healthcheck cleanup to detect and remove dead servers.</p>
 *
 * <pre>{@code
 * // Get a proxy and inspect it
 * Proxy proxy = client.getProxyById("proxy-eu").await().orElseThrow();
 *
 * // List connected players
 * Map<UUID, Long> players = proxy.getConnectedUsers().await();
 * System.out.println("Players on proxy: " + players.size());
 *
 * // Send a message to this proxy
 * new AlertMessage("Maintenance in 5 minutes").sendToProxy(proxy.getId());
 * }</pre>
 *
 * @see fr.codinbox.echo.api.EchoClient#getProxyById(String)
 * @see fr.codinbox.echo.api.EchoClient#getProxies()
 */
public interface Proxy extends Identifiable<String>, UserHolder, PropertyHolder, MessageRouter, Joinable, Cleanable {

    /**
     * Checks whether this proxy still exists in the network.
     *
     * <p>A proxy may no longer exist if it has been shut down or cleaned up
     * since this object was retrieved.</p>
     *
     * <pre>{@code
     * boolean exists = proxy.stillExists().await();
     * if (!exists) {
     *     System.out.println("Proxy has been removed from the network");
     * }
     * }</pre>
     *
     * @return a future that completes with {@code true} if the proxy is still registered
     */
    @NotNull EchoFuture<@NotNull Boolean> stillExists();

}
