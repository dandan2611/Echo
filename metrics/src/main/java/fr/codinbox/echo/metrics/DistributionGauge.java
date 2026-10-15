package fr.codinbox.echo.metrics;

import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/** A gauge exporting a {@link Distribution} as one series per {@code stat} (avg, p50, p95, max). */
public final class DistributionGauge {

    private final @NotNull Gauge gauge;

    public DistributionGauge(
            final @NotNull PrometheusRegistry registry,
            final @NotNull String name,
            final @NotNull String help,
            final @NotNull String unit) {
        this.gauge = Gauge.builder()
                .name(Objects.requireNonNull(name, "name"))
                .help(Objects.requireNonNull(help, "help") + " (" + unit + ")")
                .labelNames("stat")
                .register(registry);
    }

    public void set(final @NotNull Distribution distribution) {
        this.gauge.labelValues("avg").set(distribution.average());
        this.gauge.labelValues("p50").set(distribution.median());
        this.gauge.labelValues("p95").set(distribution.p95());
        this.gauge.labelValues("max").set(distribution.max());
    }
}
