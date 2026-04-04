package fr.codinbox.echo.api.messaging;

import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.NotNull;

/**
 * A resource that can send (publish) messages to targets on the network.
 *
 * <p>Implemented by {@link fr.codinbox.echo.api.server.Server Server} and
 * {@link fr.codinbox.echo.api.proxy.Proxy Proxy} via {@link MessageRouter}.</p>
 *
 * <pre>{@code
 * MessageTarget target = MessageTarget.server("lobby-1");
 * server.publishMessage(target, new AlertMessage("Hello from another server!"));
 * }</pre>
 *
 * @see MessageRouter
 * @see MessageTarget
 */
public interface MessageSender {

    /**
     * Publishes a message from this resource to the specified target.
     *
     * <pre>{@code
     * MessageTarget target = MessageTarget.everyone();
     * server.publishMessage(target, new AlertMessage("Broadcast!"));
     * }</pre>
     *
     * @param target  the destination target
     * @param message the message to send
     * @return a future that completes when the message has been published
     */
    @NotNull EchoFuture<Void> publishMessage(final @NotNull MessageTarget target,
                                              final @NotNull EchoMessage message);

}
