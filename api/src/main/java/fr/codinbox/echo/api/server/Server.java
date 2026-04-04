package fr.codinbox.echo.api.server;

import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.id.Identifiable;
import fr.codinbox.echo.api.messaging.MessageRouter;
import fr.codinbox.echo.api.property.PropertyHolder;
import fr.codinbox.echo.api.user.UserHolder;
import fr.codinbox.echo.api.utils.Cleanable;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a backend server (e.g. a Paper instance) in the Echo network.
 *
 * <p>A server is identified by a unique string ID (e.g. {@code "lobby-1"}, {@code "survival-2"})
 * and provides access to its connected users, custom properties, network address, and messaging.</p>
 *
 * <pre>{@code
 * // Get a server and inspect it
 * Server server = client.getServerById("lobby-1").await().orElseThrow();
 * Address addr = server.getAddress();
 * System.out.println("Server at " + addr.getHost() + ":" + addr.getPort());
 *
 * // List connected players
 * Map<UUID, Long> players = server.getConnectedUsers().await();
 * System.out.println("Online: " + players.size());
 *
 * // Set a custom property
 * PropertyKey<String> MOTD = new PropertyKey<>("motd");
 * server.setProperty(MOTD, "Welcome to the lobby!").await();
 *
 * // Send a message to this server
 * new AlertMessage("Restarting in 5 minutes").sendToServer(server.getId());
 * }</pre>
 *
 * @see fr.codinbox.echo.api.EchoClient#getServerById(String)
 * @see fr.codinbox.echo.api.EchoClient#getServers()
 */
public interface Server extends Identifiable<String>, UserHolder, PropertyHolder, MessageRouter, Joinable, Cleanable {

    /**
     * Checks whether this server still exists in the network.
     *
     * <p>A server may no longer exist if it has been shut down or cleaned up by
     * the healthcheck system since this object was retrieved.</p>
     *
     * <pre>{@code
     * boolean exists = server.stillExists().await();
     * if (!exists) {
     *     System.out.println("Server has been removed from the network");
     * }
     * }</pre>
     *
     * @return a future that completes with {@code true} if the server is still registered
     */
    @NotNull EchoFuture<@NotNull Boolean> stillExists();

}
