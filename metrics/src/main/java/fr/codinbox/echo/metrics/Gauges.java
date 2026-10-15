package fr.codinbox.echo.metrics;

import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * Builds a gauge snapshot from values computed at collection time, for collectors whose label sets change (a series
 * absent from the snapshot disappears instead of keeping its last value).
 */
public final class Gauges {

    private final @NotNull String name;
    private final @NotNull String help;
    private final @NotNull List<GaugeSnapshot.GaugeDataPointSnapshot> points = new ArrayList<>();

    public Gauges(final @NotNull String name, final @NotNull String help) {
        this.name = Objects.requireNonNull(name, "name");
        this.help = Objects.requireNonNull(help, "help");
    }

    /**
     * Adds one series.
     *
     * @param value the value
     * @param labels alternating label names and values
     * @return this builder
     */
    public @NotNull Gauges add(final double value, final String @NotNull ... labels) {
        this.points.add(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                .value(value)
                .labels(Labels.of(labels))
                .build());
        return this;
    }

    /**
     * Adds one series per statistic of the distribution, labelled {@code stat} (avg, p50, p95, max).
     *
     * @param distribution the distribution
     * @param labels alternating label names and values shared by the four series
     * @return this builder
     */
    public @NotNull Gauges add(final @NotNull Distribution distribution, final String @NotNull ... labels) {
        this.add(distribution.average(), withStat(labels, "avg"));
        this.add(distribution.median(), withStat(labels, "p50"));
        this.add(distribution.p95(), withStat(labels, "p95"));
        return this.add(distribution.max(), withStat(labels, "max"));
    }

    private static String @NotNull [] withStat(final String @NotNull [] labels, final @NotNull String stat) {
        final String[] result = java.util.Arrays.copyOf(labels, labels.length + 2);
        result[labels.length] = "stat";
        result[labels.length + 1] = stat;
        return result;
    }

    public @NotNull GaugeSnapshot build() {
        final GaugeSnapshot.Builder builder =
                GaugeSnapshot.builder().name(this.name).help(this.help);
        this.points.forEach(builder::dataPoint);
        return builder.build();
    }
}
