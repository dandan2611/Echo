package fr.codinbox.echo.api.messaging;

/**
 * A resource that can both send and receive messages.
 *
 * <p>Combines {@link MessageSender} (publish to targets) and {@link MessageReceiver}
 * (receive direct messages). Implemented by {@link fr.codinbox.echo.api.server.Server Server}
 * and {@link fr.codinbox.echo.api.proxy.Proxy Proxy}.</p>
 *
 * <pre>{@code
 * Server server = client.getServerById("lobby-1").await().orElseThrow();
 *
 * // Send a direct message to this server
 * server.sendMessage(new AlertMessage("Hello!"));
 *
 * // Publish from this server to a target
 * server.publishMessage(MessageTarget.everyone(), new AlertMessage("Broadcast!"));
 * }</pre>
 *
 * @see MessageSender
 * @see MessageReceiver
 */
public interface MessageRouter extends MessageSender, MessageReceiver {
}
