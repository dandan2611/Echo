package fr.codinbox.echo.api.messaging;

import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.NotNull;

import java.util.function.Function;

public interface MessagingProvider {

    <T extends EchoMessage> @NotNull EchoFuture<Void> publish(final @NotNull String topic,
                                                               final @NotNull T obj);

    default <T extends EchoMessage> @NotNull EchoFuture<Void> publishAll(final @NotNull Iterable<String> topics,
                                                                          final @NotNull T obj) {
        EchoFuture<Void> future = EchoFuture.completed(null);
        for (String topic : topics)
            future = EchoFuture.of(future.thenCompose(v -> this.publish(topic, obj)));
        return future;
    }

    void waitForReply(final @NotNull EchoMessage message, final @NotNull Function<@NotNull EchoMessage, @NotNull Boolean> consumer);

    void subscribe(final @NotNull String topic, final @NotNull MessageHandler<EchoMessage> handler);

    /**
     * Subscribes to a topic with a typed message handler. Only messages of the specified type
     * will be dispatched to the handler, eliminating the need for {@code instanceof} checks.
     *
     * @param topic the topic to subscribe to
     * @param type the message type to filter for
     * @param handler the typed handler
     * @param <T> the message type
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

    boolean handleReply(final @NotNull EchoMessage message);

}
