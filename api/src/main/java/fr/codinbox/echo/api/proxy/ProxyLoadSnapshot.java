package fr.codinbox.echo.api.proxy;

import org.jetbrains.annotations.NotNull;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.Objects;

/** Proxy autoscaling telemetry. The scale-out threshold is not an admission cap. */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@class")
public record ProxyLoadSnapshot(int totalCount, int nonStaffCount, int scaleOutThreshold,
                                @NotNull Instant sampledAt, @NotNull Instant validUntil) {
    public static final int SCALE_OUT_THRESHOLD = 475;

    public ProxyLoadSnapshot {
        Objects.requireNonNull(sampledAt, "sampledAt");
        Objects.requireNonNull(validUntil, "validUntil");
        if (totalCount < 0 || nonStaffCount < 0 || nonStaffCount > totalCount || scaleOutThreshold <= 0)
            throw new IllegalArgumentException("Invalid proxy counts or threshold");
        if (!validUntil.isAfter(sampledAt))
            throw new IllegalArgumentException("validUntil must follow sampledAt");
    }

    public boolean isStale(final @NotNull Instant now) {
        return now.isBefore(sampledAt) || !now.isBefore(validUntil);
    }
}
