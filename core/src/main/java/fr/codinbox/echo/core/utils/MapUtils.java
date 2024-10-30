package fr.codinbox.echo.core.utils;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class MapUtils {

    public static <K, V, NK, NV> @NotNull Map<NK, NV> map(final @NotNull Map<K, V> map,
                                                          final @NotNull Supplier<@NotNull Map<NK, NV>> mapSupplier,
                                                          final @NotNull MapFunction<K, V, NK, NV> function) {
        final Map<NK, NV> newMap = mapSupplier.get();

        for (final Map.Entry<K, V> entry : map.entrySet()) {
            final Map.Entry<NK, NV> newEntry = function.map(entry);
            newMap.put(newEntry.getKey(), newEntry.getValue());
        }

        return newMap;
    }

    public static <V> @NotNull Map<UUID, V> mapStringToUuidKey(final @NotNull Map<String, V> map) {
        return map(map, LinkedHashMap::new, entry -> Map.entry(UUID.fromString(entry.getKey()), entry.getValue()));
    }

    public interface MapFunction<K, V, NK, NV> {

        @NotNull Map.Entry<NK, NV> map(final Map.Entry<K, V> entry);

    }

}
