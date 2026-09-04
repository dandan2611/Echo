package fr.codinbox.echo.queue;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable assignment offered to the game plugin before players are transferred. */
public record QueuePlacementAssignment(
        @NotNull UUID placementId,
        long runVersion,
        @NotNull QueueId queueId,
        @NotNull String serverId,
        @NotNull Instant preparationDeadline,
        @NotNull Map<String, Set<UUID>> requests) {

    public QueuePlacementAssignment {
        Objects.requireNonNull(placementId, "placementId");
        Objects.requireNonNull(queueId, "queueId");
        Objects.requireNonNull(preparationDeadline, "preparationDeadline");
        if (runVersion < 0)
            throw new IllegalArgumentException("runVersion must not be negative");
        if (Objects.requireNonNull(serverId, "serverId").isBlank())
            throw new IllegalArgumentException("serverId must not be blank");
        if (Objects.requireNonNull(requests, "requests").isEmpty())
            throw new IllegalArgumentException("requests must not be empty");
        Map<String, Set<UUID>> snapshot = new LinkedHashMap<>();
        requests.forEach((requestId, members) -> {
            if (Objects.requireNonNull(requestId, "requestId").isBlank())
                throw new IllegalArgumentException("requestId must not be blank");
            Set<UUID> memberSnapshot = Set.copyOf(Objects.requireNonNull(members, "members"));
            if (memberSnapshot.isEmpty())
                throw new IllegalArgumentException("members must not be empty");
            snapshot.put(requestId, memberSnapshot);
        });
        requests = Map.copyOf(snapshot);
    }
}
