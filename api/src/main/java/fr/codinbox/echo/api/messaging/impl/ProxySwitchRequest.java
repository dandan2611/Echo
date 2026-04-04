package fr.codinbox.echo.api.messaging.impl;

import fr.codinbox.echo.api.messaging.EchoMessage;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A message requesting one or more players to be transferred to a different proxy.
 *
 * <p>This message is sent to the proxy that the target players are currently connected to.
 * That proxy handles the transfer by redirecting the players to the new proxy.</p>
 *
 * <p>For most use cases, use {@link fr.codinbox.echo.api.user.User#tryConnectToProxy(fr.codinbox.echo.api.proxy.Proxy)}
 * instead of constructing this message directly:</p>
 *
 * <pre>{@code
 * // Preferred: use the User API
 * Proxy targetProxy = client.getProxyById("proxy-us").await().orElseThrow();
 * user.tryConnectToProxy(targetProxy).await();
 *
 * // Low-level: construct the request manually
 * ProxySwitchRequest request = new ProxySwitchRequest("proxy-us", playerUuid);
 * request.sendToProxy("proxy-eu"); // send to the player's current proxy
 * }</pre>
 *
 * @see fr.codinbox.echo.api.user.User#tryConnectToProxy(fr.codinbox.echo.api.proxy.Proxy)
 */
@NoArgsConstructor
@Getter
@Setter
public class ProxySwitchRequest extends EchoMessage {

    /**
     * The identifier of the target proxy to transfer players to.
     */
    private @NotNull String proxyId;

    /**
     * The UUIDs of the players to transfer.
     */
    private @NotNull UUID[] userUuids;

    /**
     * Creates a proxy switch request for one or more players.
     *
     * @param proxyId   the target proxy identifier
     * @param userUuids the UUIDs of the players to transfer
     */
    public ProxySwitchRequest(final @NotNull String proxyId,
                               final @NotNull UUID... userUuids) {
        this.proxyId = proxyId;
        this.userUuids = userUuids;
    }

    /**
     * Creates a proxy switch request for a single player.
     *
     * @param proxyId  the target proxy identifier
     * @param userUuid the UUID of the player to transfer
     */
    public ProxySwitchRequest(final @NotNull String proxyId,
                               final @NotNull UUID userUuid) {
        this.proxyId = proxyId;
        this.userUuids = new UUID[] { userUuid };
    }

    // TODO: Cookie support

}
