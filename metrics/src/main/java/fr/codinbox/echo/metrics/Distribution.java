package fr.codinbox.echo.metrics;

import java.util.Arrays;
import org.jetbrains.annotations.NotNull;

/**
 * Summary of a small sample (player pings, tick durations) exported as gauges labelled by {@code stat}.
 *
 * @param count the number of values
 * @param average the mean, 0 without values
 * @param median the 50th percentile, 0 without values
 * @param p95 the 95th percentile (nearest rank), 0 without values
 * @param max the maximum, 0 without values
 */
public record Distribution(int count, double average, double median, double p95, double max) {

    public static final @NotNull Distribution EMPTY = new Distribution(0, 0, 0, 0, 0);

    public static @NotNull Distribution of(final double @NotNull [] values) {
        if (values.length == 0) return EMPTY;
        final double[] sorted = values.clone();
        Arrays.sort(sorted);
        double sum = 0;
        for (final double value : sorted) sum += value;
        return new Distribution(
                sorted.length, sum / sorted.length, rank(sorted, 0.50), rank(sorted, 0.95), sorted[sorted.length - 1]);
    }

    private static double rank(final double @NotNull [] sorted, final double quantile) {
        final int index = (int) Math.ceil(quantile * sorted.length) - 1;
        return sorted[Math.clamp(index, 0, sorted.length - 1)];
    }
}
