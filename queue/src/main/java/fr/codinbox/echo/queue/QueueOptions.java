package fr.codinbox.echo.queue;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Objects;

/** Bounded coordination and handoff timings. */
public record QueueOptions(
        @NotNull Duration pollInterval,
        @NotNull Duration claimTtl,
        @NotNull Duration reservationTtl,
        @NotNull Duration paperAckTimeout,
        @NotNull Duration transferTimeout,
        @NotNull Duration transferReconciliationTimeout) {

    public QueueOptions {
        requirePositive(pollInterval, "pollInterval");
        requirePositive(claimTtl, "claimTtl");
        requirePositive(reservationTtl, "reservationTtl");
        requirePositive(paperAckTimeout, "paperAckTimeout");
        requirePositive(transferTimeout, "transferTimeout");
        requirePositive(transferReconciliationTimeout, "transferReconciliationTimeout");
        if (reservationTtl.compareTo(paperAckTimeout) <= 0
                || reservationTtl.compareTo(transferTimeout) <= 0)
            throw new IllegalArgumentException(
                    "reservationTtl must be longer than paperAckTimeout and transferTimeout");
        if (pollInterval.compareTo(transferTimeout) >= 0)
            throw new IllegalArgumentException("pollInterval must be shorter than transferTimeout");
    }

    public static @NotNull QueueOptions defaults() {
        return new QueueOptions(Duration.ofSeconds(1), Duration.ofSeconds(30),
                Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(1));
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero() || value.toMillis() == 0)
            throw new IllegalArgumentException(name + " must be at least one millisecond");
    }
}
