package fr.codinbox.echo.api.messaging;

import org.jetbrains.annotations.NotNull;

public interface MessageHandler {

    void onReceive(final @NotNull EchoMessage message);

}
