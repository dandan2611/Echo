package fr.codinbox.echo.api.messaging;

import org.jetbrains.annotations.NotNull;

/**
 * A functional interface for handling messages received on a topic.
 *
 * <p>Used with {@link MessagingProvider#subscribe(String, MessageHandler)} and
 * {@link MessagingProvider#subscribe(String, Class, MessageHandler)} to process
 * incoming messages.</p>
 *
 * <pre>{@code
 * // Raw handler (receives all message types)
 * MessageHandler<EchoMessage> handler = message -> {
 *     System.out.println("Received: " + message.getMessageId());
 * };
 *
 * // Typed handler (only receives specific message types)
 * MessageHandler<AlertMessage> alertHandler = alert -> {
 *     System.out.println("Alert: " + alert.getText());
 * };
 * }</pre>
 *
 * @param <T> the message type this handler accepts
 * @see MessagingProvider#subscribe(String, Class, MessageHandler)
 */
@FunctionalInterface
public interface MessageHandler<T extends EchoMessage> {

    /**
     * Called when a message is received on the subscribed topic.
     *
     * @param message the received message
     */
    void onReceive(final @NotNull T message);

}
