package fr.codinbox.echo.queue.internal;

import fr.codinbox.echo.queue.QueueService;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicReference;

/** Internal process-wide registry backing {@link QueueService#load()}. */
@ApiStatus.Internal
public final class QueueServiceRegistry {

    private static final AtomicReference<QueueService> SERVICE = new AtomicReference<>();

    private QueueServiceRegistry() {
    }

    public static @NotNull QueueService load() {
        QueueService service = SERVICE.get();
        if (service == null)
            throw new IllegalStateException("QueueService is not loaded");
        return service;
    }

    public static void register(@NotNull QueueService service) {
        if (!SERVICE.compareAndSet(null, service))
            throw new IllegalStateException("A QueueService is already loaded");
    }

    public static void unregister(@NotNull QueueService service) {
        SERVICE.compareAndSet(service, null);
    }
}
