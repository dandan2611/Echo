package fr.codinbox.echo.api.server;

import fr.codinbox.echo.api.property.PropertyKey;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Destination-owned physical occupancy, independent of game participant load. True means staff. */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@class")
public record ServerAdmissionSnapshot(
        @NotNull Map<UUID, Boolean> onlineMembers,
        @NotNull Map<UUID, Boolean> joiningMembers,
        int totalCount, int nonStaffCount, int publicCapacity, int hardCapacity,
        @NotNull Instant sampledAt, @NotNull Instant validUntil) {

    public static final @NotNull String STAFF_PERMISSION = "echo.staff";
    public static final @NotNull PropertyKey<ServerAdmissionSnapshot> PROPERTY = new PropertyKey<>("admission");

    public ServerAdmissionSnapshot {
        onlineMembers = Map.copyOf(onlineMembers);
        joiningMembers = Map.copyOf(joiningMembers);
        Objects.requireNonNull(sampledAt, "sampledAt");
        Objects.requireNonNull(validUntil, "validUntil");
        if (publicCapacity <= 0 || hardCapacity < publicCapacity)
            throw new IllegalArgumentException("Require 0 < publicCapacity <= hardCapacity");
        if (totalCount != onlineMembers.size()
                || nonStaffCount != onlineMembers.values().stream().filter(staff -> !staff).count())
            throw new IllegalArgumentException("Counts must match destination online members");
        if (joiningMembers.keySet().stream().anyMatch(onlineMembers::containsKey))
            throw new IllegalArgumentException("Online and joining members must not overlap");
        if (!validUntil.isAfter(sampledAt))
            throw new IllegalArgumentException("validUntil must follow sampledAt");
    }

    public ServerAdmissionSnapshot(final @NotNull Map<UUID, Boolean> onlineMembers,
                                   final @NotNull Map<UUID, Boolean> joiningMembers,
                                   final int publicCapacity, final int hardCapacity,
                                   final @NotNull Instant sampledAt, final @NotNull Instant validUntil) {
        this(onlineMembers, joiningMembers, onlineMembers.size(),
                (int) onlineMembers.values().stream().filter(staff -> !staff).count(),
                publicCapacity, hardCapacity, sampledAt, validUntil);
    }

    public boolean isStale(final @NotNull Instant now) {
        return now.isBefore(sampledAt) || !now.isBefore(validUntil);
    }

    /** Staff may exceed the public limit, never the physical limit. */
    public boolean fits(final long total, final long nonStaff, final boolean includesNonStaffIngress) {
        return total <= hardCapacity && (!includesNonStaffIngress || nonStaff <= publicCapacity);
    }
}
