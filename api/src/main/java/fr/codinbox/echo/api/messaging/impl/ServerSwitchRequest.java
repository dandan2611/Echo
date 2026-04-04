package fr.codinbox.echo.api.messaging.impl;

import fr.codinbox.echo.api.messaging.EchoMessage;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * A message requesting one or more players to be transferred to a different server.
 *
 * <p>This message is sent from a server (or any node) to the proxy that the target players
 * are connected to. The proxy handles the actual server switch and sends back a
 * {@link Response} containing the result for each player.</p>
 *
 * <p>For most use cases, use {@link fr.codinbox.echo.api.user.User#tryConnectToServer(String)}
 * instead of constructing this message directly:</p>
 *
 * <pre>{@code
 * // Preferred: use the User API
 * User user = client.getUserById(uuid).await().orElseThrow();
 * PlayerResponse resp = user.tryConnectToServer("survival-1").await();
 *
 * // Low-level: construct the request manually
 * ServerSwitchRequest request = new ServerSwitchRequest("survival-1", playerUuid);
 * request.sendToProxy("proxy-eu");
 * Response response = request.awaitReply(Response.class).await();
 * }</pre>
 *
 * @see fr.codinbox.echo.api.user.User#tryConnectToServer(String)
 */
@NoArgsConstructor
@Getter
@Setter
public class ServerSwitchRequest extends EchoMessage {

    /**
     * The identifier of the target server to transfer players to.
     */
    private @NotNull String serverId;

    /**
     * The UUIDs of the players to transfer.
     */
    private @NotNull UUID[] userUuids;

    /**
     * Creates a server switch request for one or more players.
     *
     * @param serverId  the target server identifier
     * @param userUuids the UUIDs of the players to transfer
     */
    public ServerSwitchRequest(final @NotNull String serverId,
                               final @NotNull UUID... userUuids) {
        this.serverId = serverId;
        this.userUuids = userUuids;
    }

    /**
     * Creates a server switch request for a single player.
     *
     * @param serverId the target server identifier
     * @param userUuid the UUID of the player to transfer
     */
    public ServerSwitchRequest(final @NotNull String serverId,
                               final @NotNull UUID userUuid) {
        this.serverId = serverId;
        this.userUuids = new UUID[] { userUuid };
    }

    /**
     * The response to a {@link ServerSwitchRequest}, containing the result for each player.
     *
     * <p>Sent by the proxy back to the requesting node after processing the switch.</p>
     *
     * <pre>{@code
     * request.awaitReply(Response.class).thenAccept(response -> {
     *     response.getResponses().forEach((uuid, playerResp) -> {
     *         if (playerResp.isSuccessful()) {
     *             System.out.println(uuid + " transferred successfully");
     *         } else {
     *             System.out.println(uuid + " failed: " + playerResp.getStatus());
     *         }
     *     });
     * });
     * }</pre>
     */
    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @Setter
    public static class Response extends EchoMessage {

        /**
         * A map of player UUIDs to their individual transfer results.
         */
        private @NotNull Map<UUID, @NotNull PlayerResponse> responses;

    }

    /**
     * The result of a server switch attempt for an individual player.
     *
     * <pre>{@code
     * PlayerResponse resp = user.tryConnectToServer("survival-1").await();
     * if (resp.isSuccessful()) {
     *     System.out.println("Transfer successful!");
     * } else {
     *     System.out.println("Failed: " + resp.getStatus());
     *     if (resp.getSerializedReason() != null) {
     *         System.out.println("Reason: " + resp.getSerializedReason());
     *     }
     * }
     * }</pre>
     */
    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @Setter
    public static class PlayerResponse {

        /**
         * Whether the transfer was successful.
         */
        private boolean successful;

        /**
         * The status of the transfer attempt.
         */
        private @NotNull ServerSwitchRequestStatus status;

        /**
         * An optional serialized disconnect/kick reason from the target server.
         * Only set when the status is {@link ServerSwitchRequestStatus#SERVER_DISCONNECTED}.
         */
        private @Nullable String serializedReason;
    }

    /**
     * The possible outcomes of a server switch attempt.
     */
    public enum ServerSwitchRequestStatus {
        /**
         * The player was successfully connected to the server.
         */
        SUCCESS,
        /**
         * The player is already connected to the target server.
         */
        ALREADY_CONNECTED,
        /**
         * A connection attempt to this server is already in progress for this player.
         */
        CONNECTION_IN_PROGRESS,
        /**
         * A plugin on the proxy cancelled the connection attempt.
         */
        CONNECTION_CANCELLED,
        /**
         * The target server disconnected the player during the connection.
         * A reason may be available via {@link PlayerResponse#getSerializedReason()}.
         */
        SERVER_DISCONNECTED
    }

}
