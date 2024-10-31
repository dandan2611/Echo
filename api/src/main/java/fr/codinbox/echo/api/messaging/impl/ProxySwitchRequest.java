package fr.codinbox.echo.api.messaging.impl;

import fr.codinbox.echo.api.messaging.EchoMessage;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

@NoArgsConstructor
@Getter
@Setter
public class ProxySwitchRequest extends EchoMessage {

    private @NotNull String proxyId;
    private @NotNull UUID[] userUuids;

    public ProxySwitchRequest(final @NotNull String proxyId,
                               final @NotNull UUID... userUuids) {
        this.proxyId = proxyId;
        this.userUuids = userUuids;
    }

    public ProxySwitchRequest(final @NotNull String proxyId,
                               final @NotNull UUID userUuid) {
        this.proxyId = proxyId;
        this.userUuids = new UUID[] { userUuid };
    }

    // TODO: Cookie support

}
