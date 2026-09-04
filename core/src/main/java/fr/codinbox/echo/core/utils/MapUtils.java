package fr.codinbox.echo.core.utils;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class MapUtils {

    public static <V> @NotNull Map<UUID, V> mapStringToUuidKey(final @NotNull Map<String, V> map) {
        final Map<UUID, V> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(UUID.fromString(key), value));
        return result;
    }

}
