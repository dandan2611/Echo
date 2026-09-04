package fr.codinbox.echo.queue;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Administrative view and controls for configured queues. */
public interface QueueAdministration {

    @NotNull CompletableFuture<List<QueueOverview>> listQueues();

    @NotNull CompletableFuture<List<QueueTicket>> listTickets(@NotNull QueueId queueId);

    @NotNull CompletableFuture<Boolean> pause(@NotNull QueueId queueId, @NotNull String reason);

    @NotNull CompletableFuture<Boolean> resume(@NotNull QueueId queueId);

    @NotNull CompletableFuture<Void> wake(@NotNull QueueId queueId);

    @NotNull CompletableFuture<Boolean> retry(@NotNull QueueId queueId, @NotNull String requestId);

    @NotNull CompletableFuture<Integer> purgeTerminal(
            @NotNull QueueId queueId, @NotNull Duration olderThan);

    record QueueOverview(
            @NotNull QueueDefinition definition,
            boolean paused,
            @Nullable String pauseReason,
            @NotNull Map<QueueRequestStatus.State, Long> counts,
            @Nullable UUID placementId,
            @Nullable String serverId,
            @Nullable String runState) {

        public QueueOverview {
            Objects.requireNonNull(definition, "definition");
            counts = Map.copyOf(Objects.requireNonNull(counts, "counts"));
        }
    }

    record QueueTicket(
            @NotNull QueueRequestStatus status,
            @Nullable Instant createdAt,
            @Nullable Instant updatedAt) {

        public QueueTicket {
            Objects.requireNonNull(status, "status");
        }
    }
}
