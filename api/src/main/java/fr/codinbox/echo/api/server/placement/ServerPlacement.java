package fr.codinbox.echo.api.server.placement;

import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.property.PropertyKey;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Selects an eligible server and atomically leases seats for one indivisible group.
 *
 * <p>A reservation protects capacity while a caller prepares and transfers its members. It does
 * not move players and cannot make several Minecraft connections physically atomic. Callers must
 * either renew the reservation while handoff is pending or release it when the handoff fails.</p>
 */
public interface ServerPlacement {

    /** Integer server property defining the maximum participant capacity used by placement. */
    @NotNull PropertyKey<Integer> PROPERTY_CAPACITY = new PropertyKey<>("placement_capacity");

    /**
     * Selects an eligible server and atomically reserves capacity for every request member.
     * Replaying an active request with the same ID and payload returns the same reservation;
     * reusing that ID with a different payload fails.
     *
     * @param request placement criteria, members, policy, and lease duration
     * @return the reservation, or empty when no eligible server can fit the whole group
     */
    @NotNull EchoFuture<@NotNull Optional<Reservation>> reserve(@NotNull Request request);

    /**
     * Extends an active reservation without selecting another server.
     *
     * @param reservation reservation and ownership token to renew
     * @param lease new lease duration starting when the renewal is processed
     * @return the renewed reservation, or empty when it expired or its token is no longer current
     */
    @NotNull EchoFuture<@NotNull Optional<Reservation>> renew(
            @NotNull Reservation reservation, @NotNull Duration lease);

    /**
     * Releases an active reservation immediately.
     *
     * @param reservation reservation and ownership token to release
     * @return true when this call released it; false when it was absent or superseded
     */
    @NotNull EchoFuture<@NotNull Boolean> release(@NotNull Reservation reservation);

    /**
     * Lists active reservations without exposing their ownership tokens.
     *
     * @return immutable active reservation snapshots
     * @throws UnsupportedOperationException asynchronously when monitoring is not supported
     */
    default @NotNull EchoFuture<@NotNull List<ActiveReservation>> listActiveReservations() {
        return unsupportedMonitoring();
    }

    /**
     * Looks up an active reservation without exposing its ownership token.
     *
     * @param requestId reservation request ID
     * @return the active reservation, or empty when it does not exist
     * @throws UnsupportedOperationException asynchronously when monitoring is not supported
     */
    default @NotNull EchoFuture<@NotNull Optional<ActiveReservation>> findActiveReservation(
            final @NotNull String requestId) {
        return unsupportedMonitoring();
    }

    /**
     * Inspects the placement inputs and reserved capacity for one registered server.
     *
     * @param serverId Echo server ID
     * @return current status, or empty when the server is not registered
     * @throws UnsupportedOperationException asynchronously when monitoring is not supported
     */
    default @NotNull EchoFuture<@NotNull Optional<ServerStatus>> inspectServer(final @NotNull String serverId) {
        return unsupportedMonitoring();
    }

    /**
     * Evaluates a request against a consistent snapshot without creating or changing a lease.
     *
     * @param request placement request to evaluate
     * @return one evaluation per requested candidate, plus the server selected by the policy
     * @throws UnsupportedOperationException asynchronously when monitoring is not supported
     */
    default @NotNull EchoFuture<@NotNull Explanation> explain(final @NotNull Request request) {
        return unsupportedMonitoring();
    }

    private static <T> EchoFuture<T> unsupportedMonitoring() {
        return EchoFuture.of(CompletableFuture.failedFuture(
                new UnsupportedOperationException("Placement monitoring is not supported")));
    }

    /** Built-in ranking policies applied after eligibility and capacity checks. */
    enum Policy {
        /** Packs groups onto the eligible server with the greatest effective load. */
        FILL_MOST_LOADED,
        /** Distributes groups onto the eligible server with the smallest effective load. */
        SPREAD_LEAST_LOADED
    }

    /** Placement interpretation of a server's availability property. */
    enum AvailabilityState {
        /** No availability property is set, which placement treats as active for compatibility. */
        DEFAULT_ACTIVE,
        /** The server explicitly accepts new assignments. */
        ACTIVE,
        /** The server is draining and rejects new assignments. */
        DRAINING,
        /** The stored availability value is not recognized. */
        INVALID
    }

    /** Why a candidate was rejected; {@link #NONE} means it is eligible. */
    enum RejectionReason {
        /** The candidate passed every placement check. */
        NONE,
        /** The requested candidate is not registered. */
        SERVER_NOT_REGISTERED,
        /** The server heartbeat is absent or expired. */
        HEARTBEAT_MISSING,
        /** The server is draining or has an invalid availability value. */
        AVAILABILITY_REJECTED,
        /** Placement capacity is absent, malformed, or non-positive. */
        INVALID_CAPACITY,
        /** The load property is absent or malformed. */
        INVALID_LOAD,
        /** The load snapshot has reached its validity deadline. */
        STALE_LOAD,
        /** The server currently rejects queue assignments. */
        QUEUE_ASSIGNMENTS_REJECTED,
        /** At least one exact request property does not match. */
        PROPERTY_MISMATCH,
        /** The indivisible group does not fit after active reservations are counted. */
        INSUFFICIENT_CAPACITY
    }

    /**
     * One idempotent placement request for an indivisible group.
     *
     * @param requestId stable idempotency key for retries
     * @param members nonempty group that must fit on one server
     * @param candidateServerIds allowed server IDs, or an empty set to scan all registered servers
     * @param exactProperties additional server properties that must match exactly
     * @param policy ranking policy for eligible servers
     * @param lease duration for which successful capacity is reserved
     */
    record Request(
            @NotNull String requestId,
            @NotNull Set<UUID> members,
            @NotNull Set<String> candidateServerIds,
            @NotNull Map<PropertyKey<?>, Object> exactProperties,
            @NotNull Policy policy,
            @NotNull Duration lease) {

        private static final Set<String> RESERVED_FILTERS =
                Set.of("availability", "load", PROPERTY_CAPACITY.key());

        /** Validates and snapshots all request collections. */
        public Request {
            if (Objects.requireNonNull(requestId, "requestId").isBlank())
                throw new IllegalArgumentException("requestId must not be blank");
            members = Set.copyOf(Objects.requireNonNull(members, "members"));
            if (members.isEmpty())
                throw new IllegalArgumentException("members must not be empty");
            candidateServerIds = Set.copyOf(Objects.requireNonNull(candidateServerIds, "candidateServerIds"));
            if (candidateServerIds.stream().anyMatch(String::isBlank))
                throw new IllegalArgumentException("candidate server IDs must not be blank");

            final Map<PropertyKey<?>, Object> properties = new LinkedHashMap<>();
            Objects.requireNonNull(exactProperties, "exactProperties").forEach((key, value) -> {
                final String name = Objects.requireNonNull(key, "property key").key();
                if (name.isBlank())
                    throw new IllegalArgumentException("property key must not be blank");
                if (RESERVED_FILTERS.contains(name))
                    throw new IllegalArgumentException("Property is controlled by placement: " + name);
                properties.put(key, Objects.requireNonNull(value, "property value"));
            });
            exactProperties = Map.copyOf(properties);
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(lease, "lease");
            if (lease.isZero() || lease.isNegative() || lease.toMillis() == 0)
                throw new IllegalArgumentException("lease must be at least one millisecond");
        }
    }

    /**
     * An expiring capacity lease returned by {@link #reserve(Request)}.
     *
     * <p>The token is an opaque ownership generation used by {@link #renew(Reservation, Duration)}
     * and {@link #release(Reservation)} to prevent a stale caller from modifying a newer lease.
     * Once {@code expiresAt} is reached, the seats are no longer protected and the caller must not
     * transfer members unless it successfully renews or reserves again.</p>
     *
     * @param requestId idempotency key of the originating request
     * @param token opaque ownership generation; callers must preserve it unchanged
     * @param serverId selected Echo server ID
     * @param members immutable group whose seats are reserved
     * @param expiresAt absolute lease expiration instant
     */
    record Reservation(
            @NotNull String requestId,
            @NotNull String token,
            @NotNull String serverId,
            @NotNull Set<UUID> members,
            @NotNull Instant expiresAt) {

        /** Validates and snapshots the reserved members. */
        public Reservation {
            if (Objects.requireNonNull(requestId, "requestId").isBlank())
                throw new IllegalArgumentException("requestId must not be blank");
            if (Objects.requireNonNull(token, "token").isBlank())
                throw new IllegalArgumentException("token must not be blank");
            if (Objects.requireNonNull(serverId, "serverId").isBlank())
                throw new IllegalArgumentException("serverId must not be blank");
            members = Set.copyOf(Objects.requireNonNull(members, "members"));
            if (members.isEmpty())
                throw new IllegalArgumentException("members must not be empty");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * Read-only active reservation snapshot. It deliberately omits the ownership token required
     * by {@link #renew(Reservation, Duration)} and {@link #release(Reservation)}.
     *
     * @param requestId idempotency key of the originating request
     * @param serverId selected Echo server ID
     * @param members immutable group whose seats are reserved
     * @param expiresAt absolute lease expiration instant
     */
    record ActiveReservation(
            @NotNull String requestId,
            @NotNull String serverId,
            @NotNull Set<UUID> members,
            @NotNull Instant expiresAt) {

        /** Validates and snapshots the reservation. */
        public ActiveReservation {
            if (Objects.requireNonNull(requestId, "requestId").isBlank())
                throw new IllegalArgumentException("requestId must not be blank");
            if (Objects.requireNonNull(serverId, "serverId").isBlank())
                throw new IllegalArgumentException("serverId must not be blank");
            members = Set.copyOf(Objects.requireNonNull(members, "members"));
            if (members.isEmpty())
                throw new IllegalArgumentException("members must not be empty");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * Current inputs used to decide whether a server can accept another placement.
     * Missing or malformed numeric properties are represented by empty optionals.
     *
     * @param serverId Echo server ID
     * @param heartbeatAlive whether the server heartbeat is live
     * @param availability interpreted availability property
     * @param participantLoad reported participant count, if the load is valid
     * @param reservedSlots seats held by active placement reservations
     * @param capacity positive placement capacity, if valid
     * @param freeSlots non-negative unreserved capacity, if load and capacity are valid
     * @param loadFresh whether the load snapshot has not reached its validity deadline
     * @param acceptingQueueAssignments whether the reported load accepts queue assignments
     */
    record ServerStatus(
            @NotNull String serverId,
            boolean heartbeatAlive,
            @NotNull AvailabilityState availability,
            @NotNull OptionalInt participantLoad,
            long reservedSlots,
            @NotNull OptionalInt capacity,
            @NotNull OptionalLong freeSlots,
            boolean loadFresh,
            boolean acceptingQueueAssignments) {

        /** Validates the status snapshot. */
        public ServerStatus {
            if (Objects.requireNonNull(serverId, "serverId").isBlank())
                throw new IllegalArgumentException("serverId must not be blank");
            Objects.requireNonNull(availability, "availability");
            Objects.requireNonNull(participantLoad, "participantLoad");
            Objects.requireNonNull(capacity, "capacity");
            Objects.requireNonNull(freeSlots, "freeSlots");
            if (participantLoad.isPresent() && participantLoad.getAsInt() < 0)
                throw new IllegalArgumentException("participantLoad must not be negative");
            if (reservedSlots < 0)
                throw new IllegalArgumentException("reservedSlots must not be negative");
            if (capacity.isPresent() && capacity.getAsInt() <= 0)
                throw new IllegalArgumentException("capacity must be positive");
            if (freeSlots.isPresent() && freeSlots.getAsLong() < 0)
                throw new IllegalArgumentException("freeSlots must not be negative");
        }
    }

    /**
     * Result of evaluating one candidate server.
     *
     * @param serverId candidate Echo server ID
     * @param status server status, or empty when the candidate is not registered
     * @param rejectionReason explicit eligibility result
     * @param effectiveLoad participant load plus reserved slots, when calculable
     */
    record CandidateEvaluation(
            @NotNull String serverId,
            @NotNull Optional<ServerStatus> status,
            @NotNull RejectionReason rejectionReason,
            @NotNull OptionalLong effectiveLoad) {

        /** Validates the candidate evaluation. */
        public CandidateEvaluation {
            if (Objects.requireNonNull(serverId, "serverId").isBlank())
                throw new IllegalArgumentException("serverId must not be blank");
            status = Objects.requireNonNull(status, "status");
            Objects.requireNonNull(rejectionReason, "rejectionReason");
            Objects.requireNonNull(effectiveLoad, "effectiveLoad");
            if (effectiveLoad.isPresent() && effectiveLoad.getAsLong() < 0)
                throw new IllegalArgumentException("effectiveLoad must not be negative");
            if (rejectionReason == RejectionReason.NONE && effectiveLoad.isEmpty())
                throw new IllegalArgumentException("eligible candidates require an effectiveLoad");
        }
    }

    /**
     * Immutable dry-run result for a placement request.
     *
     * @param selectedServerId policy-selected eligible server, if any
     * @param candidates evaluations in deterministic server-ID order
     */
    record Explanation(
            @NotNull Optional<String> selectedServerId,
            @NotNull List<CandidateEvaluation> candidates) {

        /** Validates and snapshots the explanation. */
        public Explanation {
            selectedServerId = Objects.requireNonNull(selectedServerId, "selectedServerId");
            if (selectedServerId.filter(String::isBlank).isPresent())
                throw new IllegalArgumentException("selectedServerId must not be blank");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            final String selected = selectedServerId.orElse(null);
            if (selected != null && candidates.stream().noneMatch(candidate ->
                    candidate.serverId().equals(selected)
                            && candidate.rejectionReason() == RejectionReason.NONE))
                throw new IllegalArgumentException("selectedServerId must identify an eligible candidate");
        }
    }
}
