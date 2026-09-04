package fr.codinbox.echo.api.server;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Objects;

/** A timestamped server load that becomes ineligible after its validity deadline. */
public class ServerLoadSnapshot {

    private ServerLoad load;
    private Instant sampledAt;
    private Instant validUntil;

    private ServerLoadSnapshot() {
    }

    public ServerLoadSnapshot(
            final @NotNull ServerLoad load,
            final @NotNull Instant sampledAt,
            final @NotNull Instant validUntil) {
        this.load = Objects.requireNonNull(load, "load");
        this.sampledAt = Objects.requireNonNull(sampledAt, "sampledAt");
        this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
        if (this.validUntil.isBefore(this.sampledAt))
            throw new IllegalArgumentException("validUntil must not be before sampledAt");
    }

    public boolean isStale(final @NotNull Instant now) {
        return !Objects.requireNonNull(now, "now").isBefore(this.validUntil);
    }

    public @NotNull ServerLoad load() {
        return this.load;
    }

    public @NotNull Instant sampledAt() {
        return this.sampledAt;
    }

    public @NotNull Instant validUntil() {
        return this.validUntil;
    }

    public @NotNull ServerLoad getLoad() {
        return this.load;
    }

    public @NotNull Instant getSampledAt() {
        return this.sampledAt;
    }

    public @NotNull Instant getValidUntil() {
        return this.validUntil;
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object)
            return true;
        if (!(object instanceof ServerLoadSnapshot that))
            return false;
        return this.load.equals(that.load)
                && this.sampledAt.equals(that.sampledAt)
                && this.validUntil.equals(that.validUntil);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.load, this.sampledAt, this.validUntil);
    }

    @Override
    public String toString() {
        return "ServerLoadSnapshot[load=" + this.load
                + ", sampledAt=" + this.sampledAt
                + ", validUntil=" + this.validUntil + ']';
    }
}
