package fr.codinbox.echo.ondemand;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * An acquired server and, when available, the request that owns it.
 *
 * @param id Echo server ID
 * @param requestId allocation request ID, or {@code null} when the provider does not expose one
 */
public record ServerHandle(@NotNull String id, @Nullable String requestId) {

    public ServerHandle(@NotNull String id) {
        this(id, null);
    }

    public ServerHandle {
        Objects.requireNonNull(id, "id");
        if (id.isBlank())
            throw new IllegalArgumentException("id must not be blank");
        if (requestId != null && requestId.isBlank())
            throw new IllegalArgumentException("requestId must not be blank");
    }
}
