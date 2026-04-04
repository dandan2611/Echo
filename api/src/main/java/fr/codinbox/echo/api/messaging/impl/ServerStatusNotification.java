package fr.codinbox.echo.api.messaging.impl;

import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.Server;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

/**
 * A notification message broadcast when a server is registered or unregistered from the network.
 *
 * <p>This message is sent automatically by Echo when servers come online or go offline
 * (including when the healthcheck system detects a dead server). Proxies listen for these
 * notifications to update their internal server lists.</p>
 *
 * <p>You can subscribe to these notifications to react to server status changes:</p>
 *
 * <pre>{@code
 * messaging.subscribe(client.getLocalTopic(), ServerStatusNotification.class, notification -> {
 *     if (notification.getStatus() == ServerStatusNotification.Status.REGISTERED) {
 *         System.out.println("Server online: " + notification.getId()
 *             + " at " + notification.getAddress().getHost()
 *             + ":" + notification.getAddress().getPort());
 *     } else {
 *         System.out.println("Server offline: " + notification.getId());
 *     }
 * });
 * }</pre>
 */
@NoArgsConstructor
@Getter
@Setter
public class ServerStatusNotification extends EchoMessage {

    /**
     * The identifier of the server whose status changed.
     */
    private @NotNull String id;

    /**
     * The network address of the server.
     */
    private @NotNull Address address;

    /**
     * The new status of the server.
     */
    private @NotNull Status status;

    /**
     * Creates a notification with explicit server details.
     *
     * @param id      the server identifier
     * @param address the server address
     * @param status  the server status
     */
    public ServerStatusNotification(@NotNull String id, @NotNull Address address, @NotNull Status status) {
        this.id = id;
        this.address = address;
        this.status = status;
    }

    /**
     * Creates a notification from a {@link Server} object.
     *
     * @param server the server
     * @param status the server status
     */
    public ServerStatusNotification(@NotNull Server server, @NotNull Status status) {
        this.id = server.getId();
        this.address = server.getAddress();
        this.status = status;
    }

    /**
     * The possible statuses of a server in the network.
     */
    public enum Status {
        /**
         * The server has been registered (came online) in the network.
         */
        REGISTERED,
        /**
         * The server has been unregistered (went offline or was cleaned up) from the network.
         */
        UNREGISTERED,
    }


}
