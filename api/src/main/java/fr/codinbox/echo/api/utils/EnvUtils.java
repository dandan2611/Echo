package fr.codinbox.echo.api.utils;

import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.server.Address;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class EnvUtils {

    public static final @NotNull String ENV_RESOURCE_TYPE = "ECHO_RESOURCE_TYPE";
    public static final @NotNull String ENV_RESOURCE_ID = "ECHO_RESOURCE_ID";
    public static final @NotNull String ENV_RESOURCE_ADDRESS = "ECHO_RESOURCE_ADDRESS";

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

}
