package fr.codinbox.echo.ondemand;

import fr.codinbox.echo.api.property.PropertyKey;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * A request for one server of a given type.
 *
 * @param requestId stable idempotency key supplied by the caller
 * @param type server type matched by the orchestrator
 * @param properties Echo properties written before the acquired server is returned
 */
public record ServerRequest(@NotNull String requestId,
                            @NotNull String type,
                            @NotNull Map<? extends PropertyKey<?>, ?> properties) {

    /**
     * Creates a request without initial Echo properties.
     *
     * @param requestId stable idempotency key supplied by the caller
     * @param type server type matched by the orchestrator
     */
    public ServerRequest(final @NotNull String requestId, final @NotNull String type) {
        this(requestId, type, Map.of());
    }

    /** Validates the request and snapshots its properties. */
    public ServerRequest {
        if (requestId.isBlank())
            throw new IllegalArgumentException("requestId must not be blank");
        if (type.isBlank())
            throw new IllegalArgumentException("type must not be blank");
        properties = Map.copyOf(properties);
        if (properties.keySet().stream().anyMatch(key -> key.key().isBlank()))
            throw new IllegalArgumentException("property keys must not be blank");
    }
}
