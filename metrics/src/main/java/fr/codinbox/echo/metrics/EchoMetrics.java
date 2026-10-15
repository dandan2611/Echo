package fr.codinbox.echo.metrics;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.utils.EnvUtils;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Info;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Prometheus exporter of an Echo node.
 *
 * <p>Every node exports JVM metrics and an {@code echo_resource_info} series; platforms register their own metrics in
 * {@link #registry()}. With {@link EchoMetricsSettings#network()}, the node also exports the network-wide view read
 * from Redis ({@code echo_network_*}); every exporting node reports the same values, so aggregate them with
 * {@code max}. Nodes running a {@code QueueService} export its queues ({@code echo_queue_*}).
 */
public final class EchoMetrics implements AutoCloseable {

    private static final @NotNull String NETWORK = "network";
    private static final @NotNull String QUEUE = "queue";

    private final @NotNull EchoMetricsSettings settings;
    private final @NotNull EchoClient client;
    private final @NotNull Logger logger;
    private final @NotNull Clock clock;
    private final @NotNull PrometheusRegistry registry;
    private final @NotNull AtomicReference<NetworkSnapshot> network = new AtomicReference<>();
    private final @NotNull AtomicReference<QueueSnapshot> queues = new AtomicReference<>();
    private final @NotNull Counter collectionFailures;
    private final @NotNull Gauge collectionDuration;
    private final @NotNull Gauge lastCollection;
    private final @NotNull ScheduledExecutorService scheduler;
    private @Nullable HTTPServer server;

    EchoMetrics(
            final @NotNull EchoMetricsSettings settings,
            final @NotNull EchoClient client,
            final @NotNull Logger logger,
            final @NotNull Clock clock,
            final @NotNull PrometheusRegistry registry) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.client = Objects.requireNonNull(client, "client");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.collectionFailures = Counter.builder()
                .name("echo_metrics_collection_failures")
                .help("Failed network or queue collections")
                .labelNames("source")
                .register(registry);
        this.collectionDuration = Gauge.builder()
                .name("echo_metrics_collection_duration_seconds")
                .help("Duration of the last successful network or queue collection")
                .labelNames("source")
                .register(registry);
        this.lastCollection = Gauge.builder()
                .name("echo_metrics_last_collection_timestamp_seconds")
                .help("Unix time of the last successful network or queue collection")
                .labelNames("source")
                .register(registry);
        final String group = EnvUtils.getInitialProperties()
                .getOrDefault(new PropertyKey<String>(settings.groupProperty()), NetworkSnapshot.UNKNOWN_GROUP);
        Info.builder()
                .name("echo_resource_info")
                .help("Echo resource exported by this node")
                .labelNames("resource_type", "resource_id", "group")
                .register(registry)
                .setLabelValues(
                        client.getCurrentResourceType().name(),
                        client.getCurrentResourceId().orElse(""),
                        group);
        registry.register(this::collectSnapshots);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "echo-metrics");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts the exporter when {@link EchoMetricsSettings#enabled()} is set.
     *
     * @param client the started Echo client of this node
     * @param logger the platform logger
     * @return the running exporter, or empty when disabled
     * @throws IOException if the HTTP port cannot be bound
     */
    public static @NotNull Optional<EchoMetrics> startFromEnvironment(
            final @NotNull EchoClient client, final @NotNull Logger logger) throws IOException {
        final EchoMetricsSettings settings = EchoMetricsSettings.fromEnvironment();
        if (!settings.enabled()) return Optional.empty();
        return Optional.of(start(settings, client, logger));
    }

    public static @NotNull EchoMetrics start(
            final @NotNull EchoMetricsSettings settings, final @NotNull EchoClient client, final @NotNull Logger logger)
            throws IOException {
        final PrometheusRegistry registry = new PrometheusRegistry();
        JvmMetrics.builder().register(registry);
        final EchoMetrics metrics = new EchoMetrics(settings, client, logger, Clock.systemUTC(), registry);
        try {
            metrics.server = HTTPServer.builder()
                    .port(settings.port())
                    .registry(registry)
                    .buildAndStart();
        } catch (IOException | RuntimeException error) {
            metrics.scheduler.shutdownNow();
            throw error;
        }
        metrics.scheduler.scheduleWithFixedDelay(
                metrics::refresh, 0L, settings.networkInterval().toMillis(), TimeUnit.MILLISECONDS);
        logger.info("Echo metrics listening on port "
                + metrics.server.getPort()
                + (settings.network() ? " with the network view" : ""));
        return metrics;
    }

    /** Registry receiving the platform metrics of this node. */
    public @NotNull PrometheusRegistry registry() {
        return this.registry;
    }

    public @NotNull EchoMetricsSettings settings() {
        return this.settings;
    }

    /** Collects the network view and the local queues once. Called periodically by the exporter. */
    void refresh() {
        if (this.settings.network())
            this.network.set(this.collect(
                    NETWORK,
                    () -> NetworkSnapshot.read(this.client, this.settings.groupProperty())
                            .get(this.settings.networkInterval().toMillis(), TimeUnit.MILLISECONDS)));
        this.queues.set(this.collect(
                QUEUE,
                () -> QueueSource.read(this.clock.instant())
                        .map(future -> {
                            try {
                                return future.get(
                                        this.settings.networkInterval().toMillis(), TimeUnit.MILLISECONDS);
                            } catch (InterruptedException | ExecutionException | TimeoutException error) {
                                throw new CollectionException(error);
                            }
                        })
                        .orElse(null)));
    }

    private <T> @Nullable T collect(final @NotNull String source, final @NotNull Collection<T> collection) {
        final long started = System.nanoTime();
        try {
            final T result = collection.collect();
            if (result != null) {
                this.collectionDuration.labelValues(source).set((System.nanoTime() - started) / 1e9);
                this.lastCollection.labelValues(source).set(this.clock.millis() / 1e3);
            }
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception | LinkageError error) {
            this.collectionFailures.labelValues(source).inc();
            this.logger.log(Level.WARNING, "Failed to collect Echo " + source + " metrics", unwrap(error));
            return null;
        }
    }

    private @NotNull MetricSnapshots collectSnapshots() {
        final MetricSnapshots.Builder snapshots = MetricSnapshots.builder();
        final NetworkSnapshot networkSnapshot = this.network.get();
        if (networkSnapshot != null) NetworkMetrics.export(networkSnapshot, snapshots);
        final QueueSnapshot queueSnapshot = this.queues.get();
        if (queueSnapshot != null) QueueMetrics.export(queueSnapshot, snapshots);
        return snapshots.build();
    }

    private static @NotNull Throwable unwrap(final @NotNull Throwable error) {
        Throwable current = error;
        while ((current instanceof ExecutionException || current instanceof CollectionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    @Override
    public void close() {
        this.scheduler.shutdownNow();
        final HTTPServer running = this.server;
        this.server = null;
        if (running != null) running.close();
    }

    /** Port the exporter listens on, useful when started on port 0 in tests. */
    int port() {
        return Objects.requireNonNull(this.server, "server").getPort();
    }

    @FunctionalInterface
    private interface Collection<T> {
        @Nullable
        T collect() throws Exception;
    }

    private static final class CollectionException extends RuntimeException {
        CollectionException(final @NotNull Throwable cause) {
            super(cause);
        }
    }
}
