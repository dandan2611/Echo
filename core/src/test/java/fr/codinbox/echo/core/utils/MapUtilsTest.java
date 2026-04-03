package fr.codinbox.echo.core.utils;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class MapUtilsTest {

    @Test
    void map_shouldTransformKeysAndValues() {
        Map<String, Integer> input = Map.of("a", 1, "b", 2);

        Map<String, String> result = MapUtils.map(input, HashMap::new,
                entry -> Map.entry(entry.getKey().toUpperCase(), String.valueOf(entry.getValue())));

        assertThat(result).containsEntry("A", "1").containsEntry("B", "2");
    }

    @Test
    void map_shouldReturnEmptyMapForEmptyInput() {
        Map<String, Integer> input = Map.of();

        Map<String, String> result = MapUtils.map(input, HashMap::new,
                entry -> Map.entry(entry.getKey(), String.valueOf(entry.getValue())));

        assertThat(result).isEmpty();
    }

    @Test
    void mapStringToUuidKey_shouldConvertStringKeysToUuids() {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        Map<String, Integer> input = new LinkedHashMap<>();
        input.put(uuid1.toString(), 10);
        input.put(uuid2.toString(), 20);

        Map<UUID, Integer> result = MapUtils.mapStringToUuidKey(input);

        assertThat(result).containsEntry(uuid1, 10).containsEntry(uuid2, 20);
    }

    @Test
    void mapStringToUuidKey_shouldReturnEmptyMapForEmptyInput() {
        Map<String, Integer> input = Map.of();

        Map<UUID, Integer> result = MapUtils.mapStringToUuidKey(input);

        assertThat(result).isEmpty();
    }

    @Test
    void mapStringToUuidKey_shouldThrowForInvalidUuid() {
        Map<String, Integer> input = Map.of("not-a-uuid", 1);

        assertThatThrownBy(() -> MapUtils.mapStringToUuidKey(input))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
