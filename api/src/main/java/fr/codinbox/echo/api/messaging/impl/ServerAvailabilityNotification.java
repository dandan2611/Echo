package fr.codinbox.echo.api.messaging.impl;

import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerAvailability;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

/** Notifies proxies when a live server starts or stops accepting new players. */
@NoArgsConstructor
@Getter
@Setter
public class ServerAvailabilityNotification extends EchoMessage {

    private @NotNull String id;
    private @NotNull Address address;
    private @NotNull ServerAvailability availability;

    /**
     * Creates an availability update for a live server.
     *
     * @param server server whose routing availability changed
     * @param availability new routing availability
     */
    public ServerAvailabilityNotification(final @NotNull Server server,
                                          final @NotNull ServerAvailability availability) {
        this.id = server.getId();
        this.address = server.getAddress();
        this.availability = availability;
    }
}
