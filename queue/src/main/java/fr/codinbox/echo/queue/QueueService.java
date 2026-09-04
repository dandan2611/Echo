package fr.codinbox.echo.queue;

import fr.codinbox.echo.queue.internal.QueueServiceRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Small facade over durable Redis queue coordination and assignment handoff. */
public interface QueueService extends AutoCloseable {

    /**
     * Returns the queue service started in this process.
     *
     * @return the loaded queue service
     * @throws IllegalStateException if no queue service is started
     */
    static @NotNull QueueService load() {
        return QueueServiceRegistry.load();
    }

    @NotNull CompletableFuture<Void> start();

    @NotNull CompletableFuture<QueueRequestStatus> enqueue(@NotNull QueueRequest request);

    @NotNull CompletableFuture<Optional<QueueRequestStatus>> get(
            @NotNull QueueId queueId, @NotNull String requestId);

    @NotNull CompletableFuture<Boolean> cancel(@NotNull QueueId queueId, @NotNull String requestId);

    /**
     * Returns administrative controls when supported by this implementation.
     *
     * @return the queue administration API
     * @throws UnsupportedOperationException if this implementation does not expose administration
     */
    default @NotNull QueueAdministration administration() {
        throw new UnsupportedOperationException("Queue administration is not supported");
    }

    @Override
    void close();
}
