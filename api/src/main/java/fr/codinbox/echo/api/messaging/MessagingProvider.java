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

    void subscribe(final @NotNull String topic, final @NotNull MessageHandler handler);

    boolean handleReply(final @NotNull EchoMessage message);

}
