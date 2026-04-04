package fr.codinbox.echo.api.utils;

import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.server.Address;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class for reading Echo configuration from environment variables.
 *
 * <p>Echo is configured entirely through environment variables. This class provides
 * typed accessors with sensible defaults for each configuration option.</p>
 *
 * <h3>Required variables</h3>
 * <ul>
 *     <li>{@code ECHO_RESOURCE_TYPE} - The node type: {@code SERVER} or {@code PROXY}</li>
 *     <li>{@code ECHO_RESOURCE_ID} - Unique identifier for this node (e.g. {@code "lobby-1"})</li>
 *     <li>{@code ECHO_RESOURCE_ADDRESS} - The node's address in {@code host:port} format</li>
 * </ul>
 *
 * <h3>Healthcheck variables (optional)</h3>
 * <ul>
 *     <li>{@code ECHO_HEARTBEAT_TTL} - Heartbeat TTL in seconds (default: 30)</li>
 *     <li>{@code ECHO_HEARTBEAT_INTERVAL} - Heartbeat renewal interval in seconds (default: 10)</li>
 *     <li>{@code ECHO_SCAN_INTERVAL} - Dead resource scan interval in seconds (default: 15)</li>
 *     <li>{@code ECHO_HEALTHCHECK_CLEANUP_ENABLED} - Enable cleanup on servers (default: false;
 *         always active on proxies)</li>
 * </ul>
 *
 * @see EchoResourceType
 */
public class EnvUtils {

    /** Environment variable name for the node's resource type ({@code SERVER} or {@code PROXY}). */
    public static final @NotNull String ENV_RESOURCE_TYPE = "ECHO_RESOURCE_TYPE";

    /** Environment variable name for the node's unique identifier. */
    public static final @NotNull String ENV_RESOURCE_ID = "ECHO_RESOURCE_ID";

    /** Environment variable name for the node's network address ({@code host:port}). */
    public static final @NotNull String ENV_RESOURCE_ADDRESS = "ECHO_RESOURCE_ADDRESS";

    /** Environment variable name for the heartbeat TTL in seconds. */
    public static final @NotNull String ENV_HEARTBEAT_TTL = "ECHO_HEARTBEAT_TTL";

    /** Environment variable name for the heartbeat renewal interval in seconds. */
    public static final @NotNull String ENV_HEARTBEAT_INTERVAL = "ECHO_HEARTBEAT_INTERVAL";

    /** Environment variable name for the dead resource scan interval in seconds. */
    public static final @NotNull String ENV_SCAN_INTERVAL = "ECHO_SCAN_INTERVAL";

    /** Environment variable name for enabling healthcheck cleanup on servers. */
    public static final @NotNull String ENV_HEALTHCHECK_CLEANUP_ENABLED = "ECHO_HEALTHCHECK_CLEANUP_ENABLED";

    private static final long DEFAULT_HEARTBEAT_TTL = 30;
    private static final long DEFAULT_HEARTBEAT_INTERVAL = 10;
    private static final long DEFAULT_SCAN_INTERVAL = 15;

    /**
     * Gets the resource type from the {@code ECHO_RESOURCE_TYPE} environment variable.
     *
     * @return the resource type, or {@code null} if not set
     * @throws IllegalArgumentException if the value is not a valid {@link EchoResourceType}
     */
    public static @Nullable EchoResourceType getResourceType() {
        final String resourceType = System.getenv(ENV_RESOURCE_TYPE);

        if (resourceType == null)
            return null;
        return EchoResourceType.valueOf(resourceType);
    }

    /**
     * Gets the resource identifier from the {@code ECHO_RESOURCE_ID} environment variable.
     *
     * @return the resource identifier, or {@code null} if not set
     */
    public static @Nullable String getResourceId() {
        return System.getenv(ENV_RESOURCE_ID);
    }

    /**
     * Gets the node address from the {@code ECHO_RESOURCE_ADDRESS} environment variable.
     *
     * @return the parsed address, or {@code null} if not set
     * @throws IllegalArgumentException if the address format is invalid
     */
    public static @Nullable Address getAddress() {
        final String address = System.getenv(ENV_RESOURCE_ADDRESS);

        if (address == null)
            return null;
        return Address.fromString(address);
    }

    /**
     * Gets the heartbeat TTL in seconds from the {@code ECHO_HEARTBEAT_TTL} environment variable.
     *
     * @return the heartbeat TTL in seconds (default: 30)
     */
    public static long getHeartbeatTtl() {
        final String value = System.getenv(ENV_HEARTBEAT_TTL);
        if (value == null) return DEFAULT_HEARTBEAT_TTL;
        return Long.parseLong(value);
    }

    /**
     * Gets the heartbeat renewal interval in seconds from the {@code ECHO_HEARTBEAT_INTERVAL}
     * environment variable.
     *
     * @return the heartbeat interval in seconds (default: 10)
     */
    public static long getHeartbeatInterval() {
        final String value = System.getenv(ENV_HEARTBEAT_INTERVAL);
        if (value == null) return DEFAULT_HEARTBEAT_INTERVAL;
        return Long.parseLong(value);
    }

    /**
     * Gets the dead resource scan interval in seconds from the {@code ECHO_SCAN_INTERVAL}
     * environment variable.
     *
     * @return the scan interval in seconds (default: 15)
     */
    public static long getScanInterval() {
        final String value = System.getenv(ENV_SCAN_INTERVAL);
        if (value == null) return DEFAULT_SCAN_INTERVAL;
        return Long.parseLong(value);
    }

    /**
     * Checks whether healthcheck cleanup is enabled on this node.
     *
     * <p>Read from the {@code ECHO_HEALTHCHECK_CLEANUP_ENABLED} environment variable.
     * Defaults to {@code false}. Note that proxies always perform cleanup regardless
     * of this setting.</p>
     *
     * @return {@code true} if cleanup is enabled
     */
    public static boolean isHealthcheckCleanupEnabled() {
        final String value = System.getenv(ENV_HEALTHCHECK_CLEANUP_ENABLED);
        return Boolean.parseBoolean(value);
    }

}
