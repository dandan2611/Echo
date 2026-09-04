package fr.codinbox.echo.core.server.placement;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.server.ServerAvailability;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Redis-backed atomic placement for modest server fleets. */
public final class RedisServerPlacement implements ServerPlacement {

    static final String PLACEMENT_LOCK = "placement:lock";
    static final String RESERVATIONS_MAP = "placement:reservations";
    private static final String SERVERS_MAP = "servers:map";

    private final RedissonClient client;
    private final Clock clock;

    public RedisServerPlacement(final @NotNull RedisConnection connection) {
        this(connection.getClient(), Clock.systemUTC());
    }

    RedisServerPlacement(final @NotNull RedissonClient client, final @NotNull Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public @NotNull EchoFuture<@NotNull Optional<Reservation>> reserve(final @NotNull Request request) {
        return this.async(() -> this.withLock(() -> this.reserveLocked(request)));
    }

    @Override
    public @NotNull EchoFuture<@NotNull Optional<Reservation>> renew(
            final @NotNull Reservation reservation, final @NotNull Duration lease) {
        if (lease.isNegative() || lease.isZero() || lease.toMillis() == 0)
            throw new IllegalArgumentException("lease must be at least one millisecond");
        return this.async(() -> this.withLock(() -> {
            final RMapCache<String, String> reservations = this.reservations();
            final StoredReservation current = decode(reservations.get(reservation.requestId()));
            if (current == null || !current.reservation().token().equals(reservation.token()))
                return Optional.empty();

            final Reservation renewed = new Reservation(current.reservation().requestId(),
                    current.reservation().token(), current.reservation().serverId(),
                    current.reservation().members(), this.expiresAt(lease));
            reservations.put(renewed.requestId(), encode(new StoredReservation(current.fingerprint(), renewed)),
                    lease.toMillis(), TimeUnit.MILLISECONDS);
            return Optional.of(renewed);
        }));
    }

    @Override
    public @NotNull EchoFuture<@NotNull Boolean> release(final @NotNull Reservation reservation) {
        return this.async(() -> this.withLock(() -> {
            final RMapCache<String, String> reservations = this.reservations();
            final StoredReservation current = decode(reservations.get(reservation.requestId()));
            if (current == null || !current.reservation().token().equals(reservation.token()))
                return false;
            reservations.remove(reservation.requestId());
            return true;
        }));
    }

    @Override
    public @NotNull EchoFuture<@NotNull List<ActiveReservation>> listActiveReservations() {
        return this.async(() -> this.withLock(() -> this.reservations().readAllMap().values().stream()
                .map(RedisServerPlacement::decodeRequired)
                .map(StoredReservation::reservation)
                .map(RedisServerPlacement::view)
                .sorted(Comparator.comparing(ActiveReservation::requestId))
                .toList()));
    }

    @Override
    public @NotNull EchoFuture<@NotNull Optional<ActiveReservation>> findActiveReservation(
            final @NotNull String requestId) {
        if (Objects.requireNonNull(requestId, "requestId").isBlank())
            throw new IllegalArgumentException("requestId must not be blank");
        return this.async(() -> this.withLock(() -> Optional.ofNullable(
                        decode(this.reservations().get(requestId)))
                .map(StoredReservation::reservation)
                .map(RedisServerPlacement::view)));
    }

    @Override
    public @NotNull EchoFuture<@NotNull Optional<ServerStatus>> inspectServer(final @NotNull String serverId) {
        if (Objects.requireNonNull(serverId, "serverId").isBlank())
            throw new IllegalArgumentException("serverId must not be blank");
        return this.async(() -> this.withLock(() -> {
            if (!this.servers().readAllKeySet().contains(serverId))
                return Optional.empty();
            return Optional.of(this.serverStatus(serverId, this.clock.instant(),
                    this.reservedByServer().getOrDefault(serverId, 0L)));
        }));
    }

    @Override
    public @NotNull EchoFuture<@NotNull Explanation> explain(final @NotNull Request request) {
        Objects.requireNonNull(request, "request");
        return this.async(() -> this.withLock(() -> this.explainLocked(request)));
    }

    private Optional<Reservation> reserveLocked(final Request request) {
        final RMapCache<String, String> reservations = this.reservations();
        final String fingerprint = this.fingerprint(request);
        final StoredReservation existing = decode(reservations.get(request.requestId()));
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint))
                throw new IllegalStateException("Active placement request ID has a different payload: "
                        + request.requestId());
            return Optional.of(existing.reservation());
        }

        final Explanation explanation = this.explainLocked(request);
        if (explanation.selectedServerId().isEmpty())
            return Optional.empty();

        final Reservation reservation = new Reservation(request.requestId(), UUID.randomUUID().toString(),
                explanation.selectedServerId().orElseThrow(), request.members(), this.expiresAt(request.lease()));
        reservations.put(request.requestId(), encode(new StoredReservation(fingerprint, reservation)),
                request.lease().toMillis(), TimeUnit.MILLISECONDS);
        return Optional.of(reservation);
    }

    private Explanation explainLocked(Request request) {
        // ponytail: one global lock and one O(reservations) scan; replace with an indexed Lua model if measured throughput requires it.
        final Map<String, Long> reservedByServer = this.reservedByServer();
        final Set<String> registered = this.servers().readAllKeySet();
        final List<String> serverIds = new ArrayList<>(request.candidateServerIds().isEmpty()
                ? registered : request.candidateServerIds());
        serverIds.sort(Comparator.naturalOrder());
        final List<CandidateEvaluation> evaluations = new ArrayList<>(serverIds.size());
        Candidate selected = null;
        final Instant now = this.clock.instant();
        for (String serverId : serverIds) {
            final CandidateEvaluation evaluation = registered.contains(serverId)
                    ? this.evaluate(serverId, request, now, reservedByServer.getOrDefault(serverId, 0L))
                    : new CandidateEvaluation(serverId, Optional.empty(), RejectionReason.SERVER_NOT_REGISTERED,
                    OptionalLong.empty());
            evaluations.add(evaluation);
            if (evaluation.rejectionReason() != RejectionReason.NONE)
                continue;
            final Candidate candidate = new Candidate(serverId, evaluation.effectiveLoad().orElseThrow());
            if (selected == null || better(candidate, selected, request.policy()))
                selected = candidate;
        }
        return new Explanation(Optional.ofNullable(selected).map(Candidate::serverId), evaluations);
    }

    private CandidateEvaluation evaluate(String serverId, Request request, Instant now, long reserved) {
        final ServerStatus status = this.serverStatus(serverId, now, reserved);
        final Optional<ServerStatus> statusView = Optional.of(status);
        if (!status.heartbeatAlive())
            return rejected(serverId, statusView, RejectionReason.HEARTBEAT_MISSING);
        if (status.availability() == AvailabilityState.DRAINING
                || status.availability() == AvailabilityState.INVALID)
            return rejected(serverId, statusView, RejectionReason.AVAILABILITY_REJECTED);
        if (status.capacity().isEmpty())
            return rejected(serverId, statusView, RejectionReason.INVALID_CAPACITY);
        if (status.participantLoad().isEmpty())
            return rejected(serverId, statusView, RejectionReason.INVALID_LOAD);
        if (!status.loadFresh())
            return rejected(serverId, statusView, RejectionReason.STALE_LOAD);
        if (!status.acceptingQueueAssignments())
            return rejected(serverId, statusView, RejectionReason.QUEUE_ASSIGNMENTS_REJECTED);
        for (Map.Entry<PropertyKey<?>, Object> filter : request.exactProperties().entrySet()) {
            if (!filter.getValue().equals(this.value(
                    "server:" + serverId + ":property:" + filter.getKey().key())))
                return rejected(serverId, statusView, RejectionReason.PROPERTY_MISMATCH);
        }
        final long effectiveLoad = Math.addExact(status.participantLoad().orElseThrow(), reserved);
        if (Math.addExact(effectiveLoad, request.members().size()) > status.capacity().orElseThrow())
            return new CandidateEvaluation(serverId, statusView, RejectionReason.INSUFFICIENT_CAPACITY,
                    OptionalLong.of(effectiveLoad));
        return new CandidateEvaluation(serverId, statusView, RejectionReason.NONE,
                OptionalLong.of(effectiveLoad));
    }

    private ServerStatus serverStatus(String serverId, Instant now, long reserved) {
        final boolean heartbeatAlive = this.client.<Object>getBucket(
                "heartbeat:server:" + serverId).remainTimeToLive() > 0;
        final Object availabilityValue = this.value("server:" + serverId + ":property:availability");
        final AvailabilityState availability = availabilityValue == null
                ? AvailabilityState.DEFAULT_ACTIVE
                : ServerAvailability.ACTIVE.name().equals(availabilityValue)
                ? AvailabilityState.ACTIVE
                : ServerAvailability.DRAINING.name().equals(availabilityValue)
                ? AvailabilityState.DRAINING : AvailabilityState.INVALID;
        final Object capacityValue = this.value("server:" + serverId + ":property:" + PROPERTY_CAPACITY.key());
        final OptionalInt capacity = capacityValue instanceof Integer value && value > 0
                ? OptionalInt.of(value) : OptionalInt.empty();
        final Object loadValue = this.value("server:" + serverId + ":property:load");
        final ServerLoadSnapshot snapshot = loadValue instanceof ServerLoadSnapshot value ? value : null;
        final OptionalInt participantLoad = snapshot == null
                ? OptionalInt.empty() : OptionalInt.of(snapshot.load().participantCount());
        final OptionalLong freeSlots = capacity.isPresent() && participantLoad.isPresent()
                ? OptionalLong.of(Math.max(0L,
                capacity.getAsInt() - reserved - participantLoad.getAsInt()))
                : OptionalLong.empty();
        return new ServerStatus(serverId, heartbeatAlive, availability, participantLoad, reserved, capacity,
                freeSlots, snapshot != null && !snapshot.isStale(now),
                snapshot != null && snapshot.load().acceptingQueueAssignments());
    }

    private Map<String, Long> reservedByServer() {
        final Map<String, Long> reservedByServer = new java.util.HashMap<>();
        this.reservations().readAllMap().values().forEach(value -> {
            final Reservation active = decodeRequired(value).reservation();
            reservedByServer.merge(active.serverId(), (long) active.members().size(),
                    (left, right) -> Math.addExact(left, right));
        });
        return reservedByServer;
    }

    private static CandidateEvaluation rejected(
            String serverId, Optional<ServerStatus> status, RejectionReason reason) {
        return new CandidateEvaluation(serverId, status, reason, OptionalLong.empty());
    }

    private Object value(String key) {
        return this.client.getBucket(key).get();
    }

    private Instant expiresAt(Duration lease) {
        return this.clock.instant().plus(lease).truncatedTo(ChronoUnit.MILLIS);
    }

    private static boolean better(Candidate candidate, Candidate selected, Policy policy) {
        if (candidate.effectiveLoad() == selected.effectiveLoad())
            return false; // Candidates are evaluated in server-ID order, so the first equal score wins.
        return policy == Policy.FILL_MOST_LOADED
                ? candidate.effectiveLoad() > selected.effectiveLoad()
                : candidate.effectiveLoad() < selected.effectiveLoad();
    }

    private String fingerprint(Request request) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, request.requestId().getBytes(StandardCharsets.UTF_8));
            request.members().stream().map(UUID::toString).sorted()
                    .forEach(value -> add(digest, value.getBytes(StandardCharsets.UTF_8)));
            request.candidateServerIds().stream().sorted()
                    .forEach(value -> add(digest, value.getBytes(StandardCharsets.UTF_8)));
            request.exactProperties().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(PropertyKey::key)))
                    .forEach(entry -> {
                        add(digest, entry.getKey().key().getBytes(StandardCharsets.UTF_8));
                        ByteBuf encoded = null;
                        try {
                            encoded = this.client.getConfig().getCodec().getValueEncoder().encode(entry.getValue());
                            final byte[] bytes = new byte[encoded.readableBytes()];
                            encoded.getBytes(encoded.readerIndex(), bytes);
                            add(digest, bytes);
                        } catch (Exception error) {
                            throw new IllegalStateException("Failed to encode placement property " + entry.getKey(), error);
                        } finally {
                            if (encoded != null)
                                encoded.release();
                        }
                    });
            add(digest, request.policy().name().getBytes(StandardCharsets.UTF_8));
            add(digest, Long.toString(request.lease().toMillis()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void add(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private RMap<String, Long> servers() {
        return this.client.getMap(SERVERS_MAP);
    }

    private RMapCache<String, String> reservations() {
        return this.client.getMapCache(RESERVATIONS_MAP);
    }

    private <T> T withLock(Supplier<T> action) {
        final RLock lock = this.client.getLock(PLACEMENT_LOCK);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private <T> EchoFuture<T> async(Supplier<T> action) {
        final EchoFuture<T> future = new EchoFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                future.complete(action.get());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    private static String encode(StoredReservation stored) {
        final Reservation reservation = stored.reservation();
        final String members = reservation.members().stream().map(UUID::toString).sorted()
                .reduce((left, right) -> left + "," + right).orElseThrow();
        return String.join("\n", stored.fingerprint(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                        reservation.requestId().getBytes(StandardCharsets.UTF_8)),
                reservation.token(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                        reservation.serverId().getBytes(StandardCharsets.UTF_8)),
                Long.toString(reservation.expiresAt().toEpochMilli()), members);
    }

    private static StoredReservation decode(String value) {
        return value == null ? null : decodeRequired(value);
    }

    private static StoredReservation decodeRequired(String value) {
        final String[] fields = value.split("\n", -1);
        if (fields.length != 6)
            throw new IllegalStateException("Corrupt placement reservation");
        final Set<UUID> members = new LinkedHashSet<>();
        for (String member : fields[5].split(","))
            members.add(UUID.fromString(member));
        final Reservation reservation = new Reservation(
                new String(Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8), fields[2],
                new String(Base64.getUrlDecoder().decode(fields[3]), StandardCharsets.UTF_8),
                members, Instant.ofEpochMilli(Long.parseLong(fields[4])));
        return new StoredReservation(fields[0], reservation);
    }

    private static ActiveReservation view(Reservation reservation) {
        return new ActiveReservation(reservation.requestId(), reservation.serverId(),
                reservation.members(), reservation.expiresAt());
    }

    private record Candidate(String serverId, long effectiveLoad) {
    }

    private record StoredReservation(String fingerprint, Reservation reservation) {
    }
}
