package fr.codinbox.echo.agones;

import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Keeps an in-pod game server synchronized with its Agones SDK sidecar.
 */
public final class AgonesGameServerLifecycle implements AutoCloseable {

    /** Default annotation used by Fleet allocation overflow to request draining. */
    public static final String DEFAULT_DRAIN_ANNOTATION = "echo.codinbox.fr/draining";

    private final AgonesSdkClient sdk;
    private final boolean longLived;
    private final String drainAnnotation;
    private final Supplier<CompletableFuture<Void>> onDrain;
    private final Duration healthInterval;
    private final Duration drainInterval;
    private final Duration startupTimeout;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean drainSignalled = new AtomicBoolean();
    private final AtomicBoolean drainInProgress = new AtomicBoolean();
    private final AtomicBoolean drainPolling = new AtomicBoolean();
    private final AtomicBoolean telemetryInProgress = new AtomicBoolean();

    /**
     * Creates a lifecycle using the Agones sidecar port injected into the pod.
     *
     * @param longLived whether this server should self-allocate and observe drain requests
     * @param drainAnnotation annotation that signals a drain
     * @param onDrain callback that removes the server from new-player routing
     * @return an in-pod lifecycle
     */
    public static @NotNull AgonesGameServerLifecycle inPod(
            final boolean longLived,
            final @NotNull String drainAnnotation,
            final @NotNull Supplier<CompletableFuture<Void>> onDrain) {
        final String port = Objects.requireNonNull(System.getenv("AGONES_SDK_HTTP_PORT"),
                "AGONES_SDK_HTTP_PORT is not set");
        return new AgonesGameServerLifecycle(
                new AgonesSdkClient(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + port)),
                longLived,
                drainAnnotation,
                onDrain,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofSeconds(30));
    }

    AgonesGameServerLifecycle(
            final AgonesSdkClient sdk,
            final boolean longLived,
            final String drainAnnotation,
            final Supplier<CompletableFuture<Void>> onDrain,
            final Duration healthInterval,
            final Duration drainInterval,
            final Duration startupTimeout) {
        this.sdk = sdk;
        this.longLived = longLived;
        this.drainAnnotation = drainAnnotation;
        this.onDrain = onDrain;
        this.healthInterval = healthInterval;
        this.drainInterval = drainInterval;
        this.startupTimeout = startupTimeout;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "echo-agones-lifecycle");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts health reporting, marks the server ready, and optionally protects it as long-lived.
     *
     * @return completion of startup
     */
    public @NotNull CompletableFuture<Void> start() {
        final long deadline = System.nanoTime() + this.startupTimeout.toNanos();
        scheduleHealth();
        return retryUntil(() -> this.sdk.isInState("Allocated").thenCompose(allocated -> allocated
                        ? CompletableFuture.completedFuture(null)
                        : this.sdk.ready()), deadline)
                .thenCompose(ignored -> waitForReadyOrAllocated(deadline))
                .thenCompose(ignored -> this.longLived
                        ? ensureAllocated(deadline)
                        : waitForExternalAllocation());
    }

    /**
     * Publishes one atomic SDK annotation, without Kubernetes API credentials. Call every 1-5 seconds
     * using real local occupancy. Only one SDK write is in flight at a time.
     */
    public @NotNull CompletableFuture<Void> publishTelemetry(final @NotNull Instant sampledAt,
            final int connectedPlayers, final int publicPlayers, final int publicCapacity) {
        if (this.scheduler.isShutdown() || !this.telemetryInProgress.compareAndSet(false, true))
            return CompletableFuture.completedFuture(null);
        try {
            return this.sdk.telemetry(sampledAt, connectedPlayers, publicPlayers, publicCapacity)
                    .whenComplete((ignored, error) -> this.telemetryInProgress.set(false));
        } catch (RuntimeException error) {
            this.telemetryInProgress.set(false);
            return CompletableFuture.failedFuture(error);
        }
    }

    private CompletableFuture<Void> waitForReadyOrAllocated(final long deadline) {
        return retryUntil(() -> this.sdk.isInState("Ready", "Allocated").thenCompose(matches -> matches
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(new IllegalStateException(
                        "GameServer is neither Ready nor Allocated"))), deadline);
    }

    private CompletableFuture<Void> ensureAllocated(final long deadline) {
        return retryUntil(() -> this.sdk.isInState("Allocated").thenCompose(allocated -> {
            if (allocated)
                return CompletableFuture.completedFuture(null);
            return this.sdk.allocate().exceptionallyCompose(error -> this.sdk.isInState("Allocated")
                    .thenCompose(nowAllocated -> nowAllocated
                            ? CompletableFuture.completedFuture(null)
                            : CompletableFuture.failedFuture(error)));
        }), deadline).thenCompose(ignored -> waitForState("Allocated", deadline));
    }

    private CompletableFuture<Void> waitForState(final String state, final long deadline) {
        return retryUntil(() -> this.sdk.isInState(state).thenCompose(matches -> matches
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(new IllegalStateException("GameServer is not " + state))), deadline);
    }

    private CompletableFuture<Void> waitForExternalAllocation() {
        return this.sdk.isInState("Allocated")
                .exceptionally(ignored -> false)
                .thenCompose(allocated -> {
                    if (allocated)
                        return CompletableFuture.completedFuture(null);
                    final CompletableFuture<Void> delay = new CompletableFuture<>();
                    this.scheduler.schedule(() -> delay.complete(null), 100, TimeUnit.MILLISECONDS);
                    return delay.thenCompose(ignored -> waitForExternalAllocation());
                });
    }

    private void scheduleHealth() {
        this.scheduler.scheduleWithFixedDelay(
                () -> this.sdk.health().exceptionally(ignored -> null),
                0,
                this.healthInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * Checks for an existing rollout drain request, then keeps observing future requests.
     *
     * @return whether a drain was already requested
     */
    public @NotNull CompletableFuture<Boolean> watchForDrainRequests() {
        if (!this.longLived || !this.drainPolling.compareAndSet(false, true))
            return CompletableFuture.completedFuture(this.drainSignalled.get());
        return pollDrainUntilSuccess().thenApply(drained -> {
            if (!this.scheduler.isShutdown())
                this.scheduler.scheduleWithFixedDelay(
                        () -> pollDrain().exceptionally(pollError -> false),
                        this.drainInterval.toMillis(),
                        this.drainInterval.toMillis(),
                        TimeUnit.MILLISECONDS);
            return drained;
        });
    }

    private CompletableFuture<Boolean> pollDrainUntilSuccess() {
        return pollDrain().exceptionallyCompose(error -> {
            if (this.scheduler.isShutdown())
                return CompletableFuture.failedFuture(error);
            final CompletableFuture<Void> delay = new CompletableFuture<>();
            this.scheduler.schedule(
                    () -> delay.complete(null), this.drainInterval.toMillis(), TimeUnit.MILLISECONDS);
            return delay.thenCompose(ignored -> pollDrainUntilSuccess());
        });
    }

    private CompletableFuture<Boolean> pollDrain() {
        if (this.drainSignalled.get() || !this.drainInProgress.compareAndSet(false, true))
            return CompletableFuture.completedFuture(this.drainSignalled.get());
        return this.sdk.isDrainRequested(this.drainAnnotation)
                .thenCompose(requested -> requested
                        ? this.onDrain.get().thenApply(ignored -> true)
                        : CompletableFuture.completedFuture(false))
                .thenAccept(completed -> {
                    if (completed)
                        this.drainSignalled.set(true);
                })
                .thenApply(ignored -> this.drainSignalled.get())
                .whenComplete((ignored, error) -> this.drainInProgress.set(false));
    }

    private CompletableFuture<Void> retryUntil(final Supplier<CompletableFuture<Void>> operation, final long deadline) {
        return operation.get().exceptionallyCompose(error -> {
            if (System.nanoTime() >= deadline)
                return CompletableFuture.failedFuture(new TimeoutException("Agones sidecar did not become available"));
            final CompletableFuture<Void> delay = new CompletableFuture<>();
            this.scheduler.schedule(() -> delay.complete(null), 100, TimeUnit.MILLISECONDS);
            return delay.thenCompose(ignored -> retryUntil(operation, deadline));
        });
    }

    @Override
    public void close() {
        this.scheduler.shutdownNow();
        try {
            this.sdk.shutdown().orTimeout(5, TimeUnit.SECONDS).join();
        } catch (RuntimeException ignored) {
            // The process is already stopping; Agones also detects the lost health stream.
        }
    }
}
