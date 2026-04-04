package fr.codinbox.echo.api.messaging;

import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.NotNull;

import java.util.function.Function;

/**
 * Low-level interface for the Echo pub/sub messaging system.
 *
 * <p>Provides methods to publish messages to topics, subscribe to topics with handlers,
 * and manage request/response reply correlation.</p>
 *
 * <p>For most use cases, prefer the convenience methods on {@link EchoMessage}
 * ({@link EchoMessage#sendTo(MessageTarget)}, {@link EchoMessage#sendToServer(String)}, etc.)
 * rather than using this provider directly.</p>
 *
 * <pre>{@code
 * MessagingProvider messaging = client.getMessagingProvider();
 *
 * // Subscribe to a topic with typed handling
 * messaging.subscribe("my-alerts", AlertMessage.class, alert -> {
 *     System.out.println("Alert: " + alert.getText());
 * });
 *
 * // Publish a message to a topic
 * messaging.publish("my-alerts", new AlertMessage("Hello!"));
 * }</pre>
 *
 * @see EchoMessage
 * @see fr.codinbox.echo.api.EchoClient#getMessagingProvider()
 */
public interface MessagingProvider {

    /**
     * Publishes a message to a single topic.
     *
     * <pre>{@code
     * messaging.publish("my-topic", new AlertMessage("Hello!"));
     * }</pre>
     *
     * @param topic the topic to publish to
     * @param obj   the message to publish
     * @param <T>   the message type
     * @return a future that completes when the message has been published
     */
    <T extends EchoMessage> @NotNull EchoFuture<Void> publish(final @NotNull String topic,
                                                               final @NotNull T obj);

    /**
     * Publishes a message to multiple topics sequentially.
     *
     * <pre>{@code
     * List<String> topics = List.of("server:lobby-1", "server:lobby-2");
     * messaging.publishAll(topics, new AlertMessage("Hello!"));
     * }</pre>
     *
     * @param topics the topics to publish to
     * @param obj    the message to publish
     * @param <T>    the message type
     * @return a future that completes when the message has been published to all topics
     */
    default <T extends EchoMessage> @NotNull EchoFuture<Void> publishAll(final @NotNull Iterable<String> topics,
                                                                          final @NotNull T obj) {
        EchoFuture<Void> future = EchoFuture.completed(null);
        for (String topic : topics)
            future = EchoFuture.of(future.thenCompose(v -> this.publish(topic, obj)));
        return future;
    }

    /**
     * Registers a raw reply handler for a message.
     *
     * <p>When a reply with the same {@code messageId} is received,
     * the consumer is invoked. It should return {@code true} to accept the reply (and stop
     * listening) or {@code false} to keep waiting for another reply.</p>
     *
     * <p>Prefer using {@link EchoMessage#onReply(Class, java.util.function.Consumer)} or
     * {@link EchoMessage#awaitReply(Class)} for typed reply handling.</p>
     *
     * @param message  the original message to wait for replies to
     * @param consumer a function that processes replies and returns whether to stop listening
     */
    void waitForReply(final @NotNull EchoMessage message, final @NotNull Function<@NotNull EchoMessage, @NotNull Boolean> consumer);

    /**
     * Subscribes to a topic with a raw message handler.
     *
     * <p>All messages published to the topic will be dispatched to the handler,
     * regardless of their type. For type-safe handling, use
     * {@link #subscribe(String, Class, MessageHandler)} instead.</p>
     *
     * <pre>{@code
     * messaging.subscribe("my-topic", message -> {
     *     System.out.println("Received message: " + message.getMessageId());
     * });
     * }</pre>
     *
     * @param topic   the topic to subscribe to
     * @param handler the handler to invoke for each received message
     */
    void subscribe(final @NotNull String topic, final @NotNull MessageHandler<EchoMessage> handler);

    /**
     * Subscribes to a topic with a typed message handler.
     *
     * <p>Only messages of the specified type will be dispatched to the handler,
     * eliminating the need for {@code instanceof} checks. Messages of other types
     * published to the same topic are silently ignored by this subscription.</p>
     *
     * <pre>{@code
     * // Only receives AlertMessage instances
     * messaging.subscribe("my-topic", AlertMessage.class, alert -> {
     *     System.out.println("Alert: " + alert.getText());
     * });
     *
     * // Multiple typed subscriptions on the same topic
     * messaging.subscribe("events", PlayerJoinEvent.class, event -> { ... });
     * messaging.subscribe("events", PlayerQuitEvent.class, event -> { ... });
     * }</pre>
     *
     * @param topic   the topic to subscribe to
     * @param type    the message type class to filter for
     * @param handler the typed handler to invoke for matching messages
     * @param <T>     the message type
     */
    default <T extends EchoMessage> void subscribe(final @NotNull String topic,
                                                   final @NotNull Class<T> type,
                                                   final @NotNull MessageHandler<T> handler) {
        this.subscribe(topic, message -> {
            if (type.isInstance(message)) {
                handler.onReceive(type.cast(message));
            }
        });
    }

    /**
     * Attempts to dispatch a message as a reply to a pending request.
     *
     * <p><b>Internal use only.</b> This is called by the messaging system when a message
     * is received to check if it is a reply to an outstanding request registered via
     * {@link #waitForReply(EchoMessage, Function)}.</p>
     *
     * @param message the message to check as a potential reply
     * @return {@code true} if the message was handled as a reply, {@code false} otherwise
     */
    boolean handleReply(final @NotNull EchoMessage message);

}
