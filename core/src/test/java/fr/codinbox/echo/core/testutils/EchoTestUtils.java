package fr.codinbox.echo.core.testutils;

import fr.codinbox.echo.api.Echo;

import java.lang.reflect.Field;

public final class EchoTestUtils {

    public static void resetEchoClient() {
        try {
            Field f = Echo.class.getDeclaredField("client");
            f.setAccessible(true);
            f.set(null, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset Echo client", e);
        }
    }
}
