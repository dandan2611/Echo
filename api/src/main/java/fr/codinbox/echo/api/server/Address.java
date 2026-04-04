package fr.codinbox.echo.api.server;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;

/**
 * Represents a network address as a host and port pair.
 *
 * <p>Used to store the connection address of servers and proxies in the Echo network.</p>
 *
 * <pre>{@code
 * // Create from host and port
 * Address addr = new Address("127.0.0.1", 25565);
 *
 * // Parse from string
 * Address addr = Address.fromString("mc.example.com:25565");
 *
 * // Convert to Java's InetSocketAddress
 * InetSocketAddress socket = addr.toInetSocketAddress();
 * }</pre>
 */
@Getter
public class Address {

    /**
     * The hostname or IP address (e.g. {@code "127.0.0.1"}, {@code "mc.example.com"}).
     */
    private String host;

    /**
     * The port number (e.g. {@code 25565}).
     */
    private int port;

    /**
     * No-arg constructor for Jackson deserialization. Not intended for direct use.
     */
    private Address() {}

    /**
     * Creates a new address with the given host and port.
     *
     * <pre>{@code
     * Address addr = new Address("127.0.0.1", 25565);
     * }</pre>
     *
     * @param host the hostname or IP address
     * @param port the port number
     */
    public Address(final @NotNull String host, final int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Converts this address to a Java {@link InetSocketAddress}.
     *
     * <pre>{@code
     * InetSocketAddress socket = address.toInetSocketAddress();
     * // Use with Java networking APIs
     * }</pre>
     *
     * @return the equivalent {@link InetSocketAddress}
     */
    public @NotNull InetSocketAddress toInetSocketAddress() {
        return new InetSocketAddress(this.host, this.port);
    }

    /**
     * Parses an address from a {@code "host:port"} string.
     *
     * <pre>{@code
     * Address addr = Address.fromString("mc.example.com:25565");
     * // addr.getHost() => "mc.example.com"
     * // addr.getPort() => 25565
     * }</pre>
     *
     * @param address the address string in {@code "host:port"} format
     * @return the parsed address
     * @throws IllegalArgumentException if the string is not in {@code "host:port"} format
     * @throws NumberFormatException    if the port is not a valid integer
     */
    public static @NotNull Address fromString(final @NotNull String address) {
        final String[] parts = address.split(":");

        if (parts.length != 2)
            throw new IllegalArgumentException("Invalid address format: " + address);
        return new Address(parts[0], Integer.parseInt(parts[1]));
    }

}
