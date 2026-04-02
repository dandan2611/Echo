package fr.codinbox.echo.api.messaging;

import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.NotNull;

public interface MessageSender {

    /**
     * Send a message from the current source to the target.
     *
     * @param target the target
     * @param message the message
     * @return a future that completes when the message has been sent
     */
    @NotNull EchoFuture<Void> publishMessage(final @NotNull MessageTarget target,
                                              final @NotNull EchoMessage message);

}
