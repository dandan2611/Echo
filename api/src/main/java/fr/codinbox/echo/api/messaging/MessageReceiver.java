package fr.codinbox.echo.api.messaging;

import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.NotNull;

/**
 * A resource that can receive (be sent) messages directly.
 *
 * <p>Implemented by {@link fr.codinbox.echo.api.server.Server Server} and
 * {@link fr.codinbox.echo.api.proxy.Proxy Proxy} via {@link MessageRouter}.
 * Sending a message to a receiver publishes it to that resource's topic.</p>
 *
 * <pre>{@code
 * Server server = client.getServerById("lobby-1").await().orElseThrow();
 * server.sendMessage(new AlertMessage("Direct message to this server"));
 * }</pre>
 *
 * @see MessageRouter
 */
public interface MessageReceiver {

    /**
     * Sends a message directly to this resource's topic.
     *
     * <pre>{@code
     * server.sendMessage(new AlertMessage("Hello!"));
     * }</pre>
     *
     * @param message the message to send
     * @return a future that completes when the message has been published
     */
    @NotNull EchoFuture<Void> sendMessage(final @NotNull EchoMessage message);

}
