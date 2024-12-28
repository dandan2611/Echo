package fr.codinbox.echo.api;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * The Echo API.
 */
public final class Echo {

    /**
     * The client.
     */
    private static EchoClient client;

    /**
     * Gets the client.
     *
     * @return the client
     * @throws NullPointerException if the client is not initialized
     */
    public static @NotNull EchoClient getClient() {
        return Objects.requireNonNull(Echo.client, "Echo is not initialized");
    }

    /**
     * Initializes the client. Should only be called by the Echo Initialization Process.
     *
     * @param client the client
     * @throws IllegalStateException if the client is already initialized
     */
    public static void initClient(final @NotNull EchoClient client) {
        if (Echo.client != null)
            throw new IllegalStateException("The client is already initialized");
        Echo.client = client;
    }

}
