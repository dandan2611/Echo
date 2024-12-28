package fr.codinbox.echo.api.server;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;

/**
 * An address, identified by a host and a port.
 */
@Getter
public class Address {

    /**
     * The host of the address.
     */
    private String host;

    /**
     * The port of the address.
     */
    private int port;

    /**
     * Creates a new address. Should only be used by the deserializer.
     */
    private Address() {}

    /**
     * Creates a new address.
     *
     * @param host the host
     * @param port the port
     */
    public Address(final @NotNull String host, final int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Converts this address to an {@link InetSocketAddress}.
     *
     * @return the converted address
     */
    public @NotNull InetSocketAddress toInetSocketAddress() {
        return new InetSocketAddress(this.host, this.port);
    }

    /**
     * Parse an address from a string.
     *
     * @param address the address string
     * @return the parsed address
     * @throws IllegalArgumentException if the address is invalid
     */
    public static @NotNull Address fromString(final @NotNull String address) {
        final String[] parts = address.split(":");

        if (parts.length != 2)
            throw new IllegalArgumentException("Invalid address format: " + address);
        return new Address(parts[0], Integer.parseInt(parts[1]));
    }

}
