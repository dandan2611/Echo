package fr.codinbox.echo.api.messaging.impl;

import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.Server;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

@NoArgsConstructor
@Getter
@Setter
public class ServerStatusNotification extends EchoMessage {

    private @NotNull String id;
    private @NotNull Address address;
    private @NotNull Status status;

    public ServerStatusNotification(@NotNull String id, @NotNull Address address, @NotNull Status status) {
        this.id = id;
        this.address = address;
        this.status = status;
    }

    public ServerStatusNotification(@NotNull Server server, @NotNull Status status) {
        this.id = server.getId();
        this.address = server.getAddress();
        this.status = status;
    }

    public enum Status {
        REGISTERED,
        UNREGISTERED,
    }


}
