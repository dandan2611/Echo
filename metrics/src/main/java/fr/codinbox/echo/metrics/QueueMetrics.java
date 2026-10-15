package fr.codinbox.echo.metrics;

import fr.codinbox.echo.queue.QueueRequestStatus;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/** Exports a {@link QueueSnapshot} as {@code echo_queue_*} gauges. */
final class QueueMetrics {

    private QueueMetrics() {}

    static void export(final @NotNull QueueSnapshot snapshot, final @NotNull MetricSnapshots.Builder snapshots) {
        final Gauges requests = new Gauges(
                "echo_queue_requests", "Stored queue requests by state; terminal states remain until purged");
        final Gauges waiting = new Gauges("echo_queue_waiting_players", "Players in queued requests");
        final Gauges oldest = new Gauges(
                "echo_queue_oldest_waiting_seconds", "Age of the oldest queued request, 0 when nobody waits");
        final Gauges paused = new Gauges("echo_queue_paused", "1 when the queue is paused");
        for (final QueueSnapshot.QueueSample queue : snapshot.queues()) {
            for (final QueueRequestStatus.State state : QueueRequestStatus.State.values())
                requests.add(
                        queue.requests().getOrDefault(state, 0L),
                        "queue",
                        queue.queue(),
                        "state",
                        state.name().toLowerCase(Locale.ROOT));
            waiting.add(queue.waitingPlayers(), "queue", queue.queue());
            oldest.add(
                    queue.oldestWaiting() == null ? 0 : queue.oldestWaiting().toMillis() / 1e3, "queue", queue.queue());
            paused.add(queue.paused() ? 1 : 0, "queue", queue.queue());
        }
        snapshots.metricSnapshot(requests.build());
        snapshots.metricSnapshot(waiting.build());
        snapshots.metricSnapshot(oldest.build());
        snapshots.metricSnapshot(paused.build());
    }
}
