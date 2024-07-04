package fr.codinbox.echo.api.server;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;

@Getter
public class Address {

    private String host;
    private int port;

    public Address() {}

    public Address(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public @NotNull InetSocketAddress toInetSocketAddress() {
        return new InetSocketAddress(this.host, this.port);
    }

    public static @NotNull Address fromString(final @NotNull String address) {
        final String[] parts = address.split(":");

        if (parts.length != 2)
            throw new IllegalArgumentException("Invalid address format: " + address);
        return new Address(parts[0], Integer.parseInt(parts[1]));
    }

}
