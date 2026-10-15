package fr.codinbox.echo.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class DistributionTest {

    @Test
    void emptySampleIsZero() {
        assertThat(Distribution.of(new double[0])).isEqualTo(Distribution.EMPTY);
    }

    @Test
    void usesNearestRankPercentiles() {
        final double[] values = new double[20];
        for (int i = 0; i < values.length; i++) values[i] = 20 - i;

        final Distribution distribution = Distribution.of(values);

        assertThat(distribution.count()).isEqualTo(20);
        assertThat(distribution.average()).isEqualTo(10.5);
        assertThat(distribution.median()).isEqualTo(10);
        assertThat(distribution.p95()).isEqualTo(19);
        assertThat(distribution.max()).isEqualTo(20);
    }

    @Test
    void singleValue() {
        assertThat(Distribution.of(new double[] {42})).isEqualTo(new Distribution(1, 42, 42, 42, 42));
    }
}
