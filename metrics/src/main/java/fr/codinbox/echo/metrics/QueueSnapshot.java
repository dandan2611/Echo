package fr.codinbox.echo.metrics;

import fr.codinbox.echo.queue.QueueAdministration;
import fr.codinbox.echo.queue.QueueRequestStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * State of every queue configured on this node, read through {@link QueueAdministration}.
 *
 * @param queues one sample per queue
 */
public record QueueSnapshot(@NotNull List<QueueSample> queues) {

    public QueueSnapshot {
        queues = List.copyOf(Objects.requireNonNull(queues, "queues"));
    }

    /**
     * One queue.
     *
     * @param queue the queue ID
     * @param paused whether the queue is paused
     * @param requests stored requests per state, terminal states included until purged
     * @param waitingPlayers players in {@link QueueRequestStatus.State#QUEUED} requests
     * @param oldestWaiting age of the oldest queued request, or {@code null} when nobody waits
     */
    public record QueueSample(
            @NotNull String queue,
            boolean paused,
            @NotNull Map<QueueRequestStatus.State, Long> requests,
            int waitingPlayers,
            @Nullable Duration oldestWaiting) {

        public QueueSample {
            Objects.requireNonNull(queue, "queue");
            requests = Map.copyOf(Objects.requireNonNull(requests, "requests"));
        }
    }

    public static @NotNull CompletableFuture<QueueSnapshot> read(
            final @NotNull QueueAdministration administration, final @NotNull Instant now) {
        return administration.listQueues().thenCompose(overviews -> {
            final List<CompletableFuture<QueueSample>> samples = overviews.stream()
                    .map(overview -> administration
                            .listTickets(overview.definition().id())
                            .thenApply(tickets -> sample(overview, tickets, now)))
                    .toList();
            return CompletableFuture.allOf(samples.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> new QueueSnapshot(
                            samples.stream().map(CompletableFuture::join).toList()));
        });
    }

    static @NotNull QueueSample sample(
            final @NotNull QueueAdministration.QueueOverview overview,
            final @NotNull List<QueueAdministration.QueueTicket> tickets,
            final @NotNull Instant now) {
        final Map<QueueRequestStatus.State, Long> requests = new EnumMap<>(QueueRequestStatus.State.class);
        for (final QueueRequestStatus.State state : QueueRequestStatus.State.values())
            requests.put(state, overview.counts().getOrDefault(state, 0L));
        int waitingPlayers = 0;
        Instant oldest = null;
        for (final QueueAdministration.QueueTicket ticket : tickets) {
            if (ticket.status().state() != QueueRequestStatus.State.QUEUED) continue;
            waitingPlayers += ticket.status().request().members().size();
            final Instant createdAt = ticket.createdAt();
            if (createdAt != null && (oldest == null || createdAt.isBefore(oldest))) oldest = createdAt;
        }
        return new QueueSample(
                overview.definition().id().value(),
                overview.paused(),
                requests,
                waitingPlayers,
                oldest == null ? null : maxZero(Duration.between(oldest, now)));
    }

    private static @NotNull Duration maxZero(final @NotNull Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
