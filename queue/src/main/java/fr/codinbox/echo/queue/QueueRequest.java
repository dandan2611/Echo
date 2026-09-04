package fr.codinbox.echo.queue;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** One idempotent and indivisible solo/party queue request. */
public record QueueRequest(
        @NotNull String requestId,
        @NotNull QueueId queueId,
        @NotNull Set<UUID> members) {

    public QueueRequest {
        if (Objects.requireNonNull(requestId, "requestId").isBlank())
            throw new IllegalArgumentException("requestId must not be blank");
        Objects.requireNonNull(queueId, "queueId");
        members = Set.copyOf(Objects.requireNonNull(members, "members"));
        if (members.isEmpty())
            throw new IllegalArgumentException("members must not be empty");
    }
}
