package fr.codinbox.echo.metrics;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Prometheus exporter settings, read from environment variables.
 *
 * <ul>
 *   <li>{@code ECHO_METRICS_ENABLED} - Serve {@code /metrics} (default: false)
 *   <li>{@code ECHO_METRICS_PORT} - HTTP port (default: 9225)
 *   <li>{@code ECHO_METRICS_NETWORK} - Also export the network-wide view read from Redis (default: false)
 *   <li>{@code ECHO_METRICS_NETWORK_INTERVAL} - Network and queue collection period in seconds (default: 15)
 *   <li>{@code ECHO_METRICS_GROUP_PROPERTY} - Server property used as the {@code group} label (default:
 *       {@code server_type})
 * </ul>
 *
 * @param enabled whether the exporter runs
 * @param port the HTTP port, 0 for an ephemeral port
 * @param network whether this node exports the network-wide view
 * @param networkInterval the network and queue collection period
 * @param groupProperty the server property used as the {@code group} label
 */
public record EchoMetricsSettings(
        boolean enabled,
        int port,
        boolean network,
        @NotNull Duration networkInterval,
        @NotNull String groupProperty) {

    public static final @NotNull String ENV_ENABLED = "ECHO_METRICS_ENABLED";
    public static final @NotNull String ENV_PORT = "ECHO_METRICS_PORT";
    public static final @NotNull String ENV_NETWORK = "ECHO_METRICS_NETWORK";
    public static final @NotNull String ENV_NETWORK_INTERVAL = "ECHO_METRICS_NETWORK_INTERVAL";
    public static final @NotNull String ENV_GROUP_PROPERTY = "ECHO_METRICS_GROUP_PROPERTY";

    public static final int DEFAULT_PORT = 9225;
    public static final @NotNull Duration DEFAULT_NETWORK_INTERVAL = Duration.ofSeconds(15);
    public static final @NotNull String DEFAULT_GROUP_PROPERTY = "server_type";

    public EchoMetricsSettings {
        Objects.requireNonNull(networkInterval, "networkInterval");
        Objects.requireNonNull(groupProperty, "groupProperty");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port must be between 0 and 65535");
        if (networkInterval.isNegative() || networkInterval.isZero())
            throw new IllegalArgumentException("networkInterval must be positive");
        if (groupProperty.isBlank()) throw new IllegalArgumentException("groupProperty must not be blank");
    }

    public static @NotNull EchoMetricsSettings fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static @NotNull EchoMetricsSettings fromEnvironment(final @NotNull Map<String, String> environment) {
        final String port = blankToNull(environment.get(ENV_PORT));
        final String interval = blankToNull(environment.get(ENV_NETWORK_INTERVAL));
        final String group = blankToNull(environment.get(ENV_GROUP_PROPERTY));
        return new EchoMetricsSettings(
                Boolean.parseBoolean(environment.get(ENV_ENABLED)),
                port == null ? DEFAULT_PORT : Integer.parseInt(port),
                Boolean.parseBoolean(environment.get(ENV_NETWORK)),
                interval == null ? DEFAULT_NETWORK_INTERVAL : Duration.ofSeconds(Long.parseLong(interval)),
                group == null ? DEFAULT_GROUP_PROPERTY : group);
    }

    private static @Nullable String blankToNull(final @Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
