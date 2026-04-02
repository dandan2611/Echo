package fr.codinbox.echo.api.messaging;

import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.NotNull;

public interface MessageReceiver {

    @NotNull EchoFuture<Void> sendMessage(final @NotNull EchoMessage message);

}
