package fr.codinbox.echo.metrics;

import fr.codinbox.echo.queue.QueueService;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;

/** Isolates the optional queue module: only loaded when a queue collection runs. */
final class QueueSource {

    private QueueSource() {}

    /**
     * Reads the queues of the {@link QueueService} loaded in this process.
     *
     * @param now the reference time for waiting durations
     * @return the pending snapshot, or empty when no queue service with administration is loaded
     */
    static @NotNull Optional<CompletableFuture<QueueSnapshot>> read(final @NotNull Instant now) {
        final QueueService service;
        try {
            service = QueueService.load();
        } catch (IllegalStateException | NoClassDefFoundError notLoaded) {
            return Optional.empty();
        }
        try {
            return Optional.of(QueueSnapshot.read(service.administration(), now));
        } catch (UnsupportedOperationException unsupported) {
            return Optional.empty();
        }
    }
}
