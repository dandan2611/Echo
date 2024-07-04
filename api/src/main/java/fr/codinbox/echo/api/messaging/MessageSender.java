package fr.codinbox.echo.api.messaging;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public interface MessageSender {

    /**
     * Send a message from the current source to the target.
     *
     * @param target the target
     * @param message the message
     * @return a future that completes when the message has been sent
     */
    @NotNull
    CompletableFuture<Void> publishMessage(final @NotNull MessageTarget target,
                                            final @NotNull EchoMessage message);

}
