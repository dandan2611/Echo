package fr.codinbox.echo.queue;

import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Current durable snapshot of a queue request. */
public record QueueRequestStatus(
        @NotNull QueueRequest request,
        long version,
        @NotNull State state,
        @Nullable UUID placementId,
        @Nullable String serverId,
        @NotNull Map<UUID, ServerSwitchRequest.PlayerResponse> responses,
        @Nullable String failure) {

    public QueueRequestStatus {
        Objects.requireNonNull(request, "request");
        if (version < 0)
            throw new IllegalArgumentException("version must not be negative");
        Objects.requireNonNull(state, "state");
        responses = Map.copyOf(Objects.requireNonNull(responses, "responses"));
    }

    public enum State {
        QUEUED,
        CLAIMED,
        PREPARED,
        TRANSFERRING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
