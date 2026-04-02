package fr.codinbox.echo.api.messaging;

import org.jetbrains.annotations.NotNull;

@FunctionalInterface
public interface MessageHandler<T extends EchoMessage> {

    void onReceive(final @NotNull T message);

}
