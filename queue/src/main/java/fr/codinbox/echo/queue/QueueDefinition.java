package fr.codinbox.echo.queue;

import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Deployment configuration for one queue. Game-start rules intentionally do not belong here. */
public record QueueDefinition(
        @NotNull QueueId id,
        @NotNull String serverType,
        @NotNull Map<PropertyKey<?>, Object> serverProperties,
        @NotNull ServerPlacement.Policy placementPolicy) {

    public QueueDefinition {
        Objects.requireNonNull(id, "id");
        if (Objects.requireNonNull(serverType, "serverType").isBlank())
            throw new IllegalArgumentException("serverType must not be blank");
        final Map<PropertyKey<?>, Object> properties = new LinkedHashMap<>();
        Objects.requireNonNull(serverProperties, "serverProperties").forEach((key, value) -> {
            if (Objects.requireNonNull(key, "property key").key().isBlank())
                throw new IllegalArgumentException("property keys must not be blank");
            properties.put(key, Objects.requireNonNull(value, "property value"));
        });
        serverProperties = Map.copyOf(properties);
        Objects.requireNonNull(placementPolicy, "placementPolicy");
    }
}
