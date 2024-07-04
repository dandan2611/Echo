package fr.codinbox.echo.api.messaging;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public interface MessageReceiver {

    @NotNull CompletableFuture<Void> sendMessage(final @NotNull EchoMessage message);

}
