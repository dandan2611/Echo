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

@NoArgsConstructor
@Getter
@Setter
public class ServerSwitchRequest extends EchoMessage {

    private @NotNull String serverId;

    private @NotNull UUID[] userUuids;

    public ServerSwitchRequest(final @NotNull String serverId,
                               final @NotNull UUID... userUuids) {
        this.serverId = serverId;
        this.userUuids = userUuids;
    }

    public ServerSwitchRequest(final @NotNull String serverId,
                               final @NotNull UUID userUuid) {
        this.serverId = serverId;
        this.userUuids = new UUID[] { userUuid };
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @Setter
    public static class Response extends EchoMessage {

        private @NotNull Map<UUID, @NotNull PlayerResponse> responses;

    }

    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @Setter
    public static class PlayerResponse {
        private boolean successful;
        private @NotNull ServerSwitchRequestStatus status;
        private @Nullable String serializedReason;
    }

    public enum ServerSwitchRequestStatus {
        /**
         * The player was successfully connected to the server.
         */
        SUCCESS,
        /**
         * The player is already connected to this server.
         */
        ALREADY_CONNECTED,
        /**
         * The connection is already in progress.
         */
        CONNECTION_IN_PROGRESS,
        /**
         * A plugin has cancelled this connection.
         */
        CONNECTION_CANCELLED,
        /**
         * The server disconnected the user. A reason may be provided.
         */
        SERVER_DISCONNECTED
    }

}
