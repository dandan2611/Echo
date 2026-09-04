package fr.codinbox.echo.api.server;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Objects;

/** A timestamped server load that becomes ineligible after its validity deadline. */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@class")
public record ServerLoadSnapshot(@NotNull ServerLoad load, @NotNull Instant sampledAt,
                                 @NotNull Instant validUntil) {

    public ServerLoadSnapshot {
        Objects.requireNonNull(load, "load");
        Objects.requireNonNull(sampledAt, "sampledAt");
        Objects.requireNonNull(validUntil, "validUntil");
        if (validUntil.isBefore(sampledAt))
            throw new IllegalArgumentException("validUntil must not be before sampledAt");
    }

    public boolean isStale(final @NotNull Instant now) {
        return !Objects.requireNonNull(now, "now").isBefore(this.validUntil);
    }

}
