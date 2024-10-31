package fr.codinbox.echo.api;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class Echo {

    private static EchoClient client;

    public static @NotNull EchoClient getClient() {
        return Objects.requireNonNull(Echo.client);
    }

    public static void initClient(final @NotNull EchoClient client) {
        Echo.client = client;
    }

}
