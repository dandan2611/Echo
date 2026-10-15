package fr.codinbox.echo.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class EchoMetricsSettingsTest {

    @Test
    void defaultsKeepTheExporterDisabled() {
        final EchoMetricsSettings settings = EchoMetricsSettings.fromEnvironment(Map.of());

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.port()).isEqualTo(9225);
        assertThat(settings.network()).isFalse();
        assertThat(settings.networkInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(settings.groupProperty()).isEqualTo("server_type");
    }

    @Test
    void readsEveryVariable() {
        final EchoMetricsSettings settings = EchoMetricsSettings.fromEnvironment(Map.of(
                "ECHO_METRICS_ENABLED", "true",
                "ECHO_METRICS_PORT", "9300",
                "ECHO_METRICS_NETWORK", "true",
                "ECHO_METRICS_NETWORK_INTERVAL", "30",
                "ECHO_METRICS_GROUP_PROPERTY", "mode"));

        assertThat(settings).isEqualTo(new EchoMetricsSettings(true, 9300, true, Duration.ofSeconds(30), "mode"));
    }

    @Test
    void blankValuesUseDefaults() {
        final EchoMetricsSettings settings = EchoMetricsSettings.fromEnvironment(
                Map.of("ECHO_METRICS_PORT", " ", "ECHO_METRICS_GROUP_PROPERTY", ""));

        assertThat(settings.port()).isEqualTo(9225);
        assertThat(settings.groupProperty()).isEqualTo("server_type");
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> EchoMetricsSettings.fromEnvironment(Map.of("ECHO_METRICS_PORT", "70000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EchoMetricsSettings.fromEnvironment(Map.of("ECHO_METRICS_NETWORK_INTERVAL", "0")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
