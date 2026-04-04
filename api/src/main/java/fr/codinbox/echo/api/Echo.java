package fr.codinbox.echo.api;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Static entry point for the Echo API.
 *
 * <p>This class provides access to the singleton {@link EchoClient} instance, which is the main
 * gateway for all Echo operations (querying users, servers, proxies, messaging, etc.).</p>
 *
 * <p>The client is automatically initialized by the Echo platform plugin (Paper or Velocity).
 * In most cases, you only need to call {@link #getClient()}:</p>
 *
 * <pre>{@code
 * EchoClient client = Echo.getClient();
 * Optional<User> user = client.getUserById(uuid).await();
 * }</pre>
 *
 * @see EchoClient
 */
public final class Echo {

    /**
     * The singleton Echo client instance. {@code null} until initialized by the platform plugin.
     */
    private static EchoClient client;

    /**
     * Returns the initialized Echo client.
     *
     * <pre>{@code
     * EchoClient client = Echo.getClient();
     * }</pre>
     *
     * @return the Echo client
     * @throws NullPointerException if the client has not been initialized yet
     */
    public static @NotNull EchoClient getClient() {
        return Objects.requireNonNull(Echo.client, "Echo is not initialized");
    }

    /**
     * Initializes the Echo client singleton.
     *
     * <p><b>Internal use only.</b> This is called automatically by the Echo platform plugin
     * (Paper or Velocity) during startup. Calling this manually will throw an exception
     * if the client is already initialized.</p>
     *
     * @param client the client implementation to register
     * @throws IllegalStateException if the client is already initialized
     */
    public static void initClient(final @NotNull EchoClient client) {
        if (Echo.client != null)
            throw new IllegalStateException("The client is already initialized");
        Echo.client = client;
    }

}
