package fr.codinbox.echo.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Consistent compact presentation for operational command output. */
public final class CommandFormatter {

    public Component rows(String title, Map<String, ?> rows) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text(title, NamedTextColor.AQUA));
        rows.forEach((key, value) -> lines.add(Component.text(key + ": ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(value), NamedTextColor.WHITE))));
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    public Component list(String title, Collection<?> values) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text(title, NamedTextColor.AQUA));
        if (values.isEmpty())
            lines.add(Component.text("None", NamedTextColor.GRAY));
        else
            values.forEach(value -> lines.add(Component.text("- " + value, NamedTextColor.WHITE)));
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    public Component success(String message) {
        return Component.text("OK: " + message, NamedTextColor.GREEN);
    }

    public Component warn(String message) {
        return Component.text("WARN: " + message, NamedTextColor.YELLOW);
    }

    public Component error(String message) {
        return Component.text("ERROR: " + message, NamedTextColor.RED);
    }

    public String instant(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }

    public String instant(Instant instant) {
        return instant.toString();
    }

    public String duration(long millis) {
        if (millis == -1)
            return "none";
        if (millis == -2)
            return "missing";
        return Duration.ofMillis(millis).toString();
    }

    public String duration(Duration duration) {
        return duration.toString();
    }
}
