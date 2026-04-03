package fr.codinbox.echo.api.utils;

import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.server.Address;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class EnvUtils {

    public static final @NotNull String ENV_RESOURCE_TYPE = "ECHO_RESOURCE_TYPE";
    public static final @NotNull String ENV_RESOURCE_ID = "ECHO_RESOURCE_ID";
    public static final @NotNull String ENV_RESOURCE_ADDRESS = "ECHO_RESOURCE_ADDRESS";

    public static final @NotNull String ENV_HEARTBEAT_TTL = "ECHO_HEARTBEAT_TTL";
    public static final @NotNull String ENV_HEARTBEAT_INTERVAL = "ECHO_HEARTBEAT_INTERVAL";
    public static final @NotNull String ENV_SCAN_INTERVAL = "ECHO_SCAN_INTERVAL";
    public static final @NotNull String ENV_HEALTHCHECK_CLEANUP_ENABLED = "ECHO_HEALTHCHECK_CLEANUP_ENABLED";

    private static final long DEFAULT_HEARTBEAT_TTL = 30;
    private static final long DEFAULT_HEARTBEAT_INTERVAL = 10;
    private static final long DEFAULT_SCAN_INTERVAL = 15;

    public static @Nullable EchoResourceType getResourceType() {
        final String resourceType = System.getenv(ENV_RESOURCE_TYPE);

        if (resourceType == null)
            return null;
        return EchoResourceType.valueOf(resourceType);
    }

    public static @Nullable String getResourceId() {
        return System.getenv(ENV_RESOURCE_ID);
    }

    public static @Nullable Address getAddress() {
        final String address = System.getenv(ENV_RESOURCE_ADDRESS);

        if (address == null)
            return null;
        return Address.fromString(address);
    }

    public static long getHeartbeatTtl() {
        final String value = System.getenv(ENV_HEARTBEAT_TTL);
        if (value == null) return DEFAULT_HEARTBEAT_TTL;
        return Long.parseLong(value);
    }

    public static long getHeartbeatInterval() {
        final String value = System.getenv(ENV_HEARTBEAT_INTERVAL);
        if (value == null) return DEFAULT_HEARTBEAT_INTERVAL;
        return Long.parseLong(value);
    }

    public static long getScanInterval() {
        final String value = System.getenv(ENV_SCAN_INTERVAL);
        if (value == null) return DEFAULT_SCAN_INTERVAL;
        return Long.parseLong(value);
    }

    public static boolean isHealthcheckCleanupEnabled() {
        final String value = System.getenv(ENV_HEALTHCHECK_CLEANUP_ENABLED);
        return Boolean.parseBoolean(value);
    }

}
