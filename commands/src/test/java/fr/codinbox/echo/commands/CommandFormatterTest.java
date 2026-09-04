package fr.codinbox.echo.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class CommandFormatterTest {

    private final CommandFormatter formatter = new CommandFormatter();

    @Test
    void formatsRowsAndEmptyAndNonemptyLists() {
        assertThat(text(this.formatter.rows("Title", Map.of("answer", 42))))
                .isEqualTo("Title\nanswer: 42");
        assertThat(text(this.formatter.list("Empty", List.of()))).isEqualTo("Empty\nNone");
        assertThat(text(this.formatter.list("Values", List.of("one", "two"))))
                .isEqualTo("Values\n- one\n- two");
    }

    @Test
    void formatsSeverityTimeAndDurationValues() {
        assertThat(text(this.formatter.success("worked"))).isEqualTo("OK: worked");
        assertThat(text(this.formatter.warn("careful"))).isEqualTo("WARN: careful");
        assertThat(text(this.formatter.error("failed"))).isEqualTo("ERROR: failed");
        assertThat(this.formatter.instant(0)).isEqualTo("1970-01-01T00:00:00Z");
        assertThat(this.formatter.instant(Instant.EPOCH)).isEqualTo("1970-01-01T00:00:00Z");
        assertThat(this.formatter.duration(1_000)).isEqualTo("PT1S");
        assertThat(this.formatter.duration(-1)).isEqualTo("none");
        assertThat(this.formatter.duration(-2)).isEqualTo("missing");
        assertThat(this.formatter.duration(Duration.ofMinutes(2))).isEqualTo("PT2M");
    }

    private static String text(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
