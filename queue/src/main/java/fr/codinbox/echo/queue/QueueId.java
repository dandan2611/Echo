package fr.codinbox.echo.queue;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** Stable identifier of one configured matchmaking lane. */
public record QueueId(@NotNull String value) {

    public QueueId {
        if (Objects.requireNonNull(value, "value").isBlank())
            throw new IllegalArgumentException("Queue ID must not be blank");
    }

    @Override
    public @NotNull String toString() {
        return this.value;
    }
}
