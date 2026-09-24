package fr.codinbox.echo.core.server.placement;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.server.ServerAvailability;
import fr.codinbox.echo.api.server.ServerAdmissionSnapshot;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RBatch;
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
import java.util.function.Supplier;

/** Redis-backed atomic placement for modest server fleets. */
public final class RedisServerPlacement implements ServerPlacement {

    static final String RESERVATIONS_MAP = RedisPlacementStore.RESERVATIONS;

    private final RedissonClient client;
    private final PlacementStore store;
    private final Map<String, Publisher> publishers = new java.util.concurrent.ConcurrentHashMap<>();
    private final ThreadLocal<PlacementStore.Transaction> transaction = new ThreadLocal<>();

    public RedisServerPlacement(final @NotNull RedisConnection connection) {
        this(connection.getClient(), Clock.systemUTC());
    }

    RedisServerPlacement(final @NotNull RedissonClient client, final @NotNull Clock clock) {
        this(client, new RedisPlacementStore(client, clock));
    }

    RedisServerPlacement(final RedissonClient client, final PlacementStore store) {
        this.client = client;
        this.store = store;
    }

    /** Trusted proxy publisher only. Missing/expired permission samples always classify as non-staff. */
    public void publishStaffPermissions(final @NotNull Map<UUID, Boolean> permissions) {
        final RBatch batch = this.client.createBatch();
        permissions.forEach((member, staff) -> batch.<Boolean>getBucket("admission:staff:" + member)
                .setAsync(staff, Duration.ofSeconds(5)));
        batch.execute();
    }

    /** Atomically register a destination and its publisher, before accepting players.
     * A concurrent registration superseding the initial observation aborts this startup.
     */
    public Instant startAdmissionPublisher(final @NotNull String serverId) {
        final Publisher publisher = new Publisher(UUID.randomUUID().toString(), new java.util.concurrent.atomic.AtomicLong());
        if (publishers.putIfAbsent(serverId, publisher) != null)
            throw new IllegalStateException("Admission publisher already started for " + serverId);
        final Object[] expected = new Object[1];
        final boolean[] observed = {false};
        return this.atomic(() -> {
            final Object current = this.value(publisherKey(serverId));
            if (observed[0] && !Objects.equals(expected[0], current))
                throw new PlacementUnavailableException("Destination registration was superseded");
            expected[0] = current;
            observed[0] = true;
            final Instant registeredAt = this.transaction.get().leaseNow();
            this.transaction.get().registerServer(serverId, registeredAt);
            this.transaction.get().set(publisherKey(serverId), publisher.incarnation());
            this.transaction.get().set(sequenceKey(serverId), 0L);
            this.writeAdmission(serverId, new ServerAdmissionSnapshot(Map.of(), Map.of(), 1, 1,
                    Instant.EPOCH, Instant.EPOCH.plusSeconds(1)));
            return registeredAt;
        });
    }

    private Publisher publisher(String serverId) {
        final Publisher publisher = publishers.get(serverId);
        if (publisher == null) throw new PlacementUnavailableException("Admission publisher has not started");
        return publisher;
    }

    private boolean currentPublisher(String serverId, Publisher publisher, long sequence) {
        if (!publisher.incarnation().equals(this.value(publisherKey(serverId))))
            throw new PlacementUnavailableException("Admission publisher was replaced");
        final Object previous = this.value(sequenceKey(serverId));
        return previous instanceof Long last && sequence > last;
    }

    private static String publisherKey(String serverId) { return "placement:v2:publisher:" + serverId; }
    private static String sequenceKey(String serverId) { return "placement:v2:sequence:" + serverId; }
    private record Publisher(String incarnation, java.util.concurrent.atomic.AtomicLong sequence) { }

    /** Blocking Redis I/O: invoke on the destination's ordered background writer, never its tick thread. */
    public void publishAdmission(final @NotNull String serverId, final @NotNull ServerAdmissionSnapshot snapshot) {
        final Publisher publisher = publisher(serverId);
        final long sequence = publisher.sequence().incrementAndGet();
        this.atomic(1000, () -> {
            if (!currentPublisher(serverId, publisher, sequence)) return null;
            final Object previous = this.value("server:" + serverId + ":property:admission");
            if (!(previous instanceof ServerAdmissionSnapshot current)
                    || current.sampledAt().isBefore(snapshot.sampledAt())) {
                if (!snapshot.isStale(this.now())) this.transaction.get().validUntil(snapshot.validUntil());
                this.writeAdmission(serverId, snapshot);
                this.transaction.get().set(sequenceKey(serverId), sequence);
            }
            return null;
        });
    }

    /**
     * Final destination gate. The permission and occupancy arguments must come from the local server,
     * not a message payload. Commits against the same state as reserved seats.
     * Technical unavailability throws instead of pretending the destination is full.
     * Invoke off the tick thread and bound the caller's wait independently.
     */
    public boolean admit(final @NotNull String serverId, final @NotNull UUID member, final boolean staff,
                          final @NotNull ServerAdmissionSnapshot snapshot) {
            final Publisher publisher = publisher(serverId);
            final long sequence = publisher.sequence().incrementAndGet();
            return this.atomic(100, () -> {
                if (!currentPublisher(serverId, publisher, sequence))
                    throw new PlacementUnavailableException("Admission operation superseded");
                if (snapshot.isStale(this.now()))
                    return false;
                this.transaction.get().validUntil(snapshot.validUntil());
                final Object availability = this.value("server:" + serverId + ":property:availability");
                if (availability != null && !ServerAvailability.ACTIVE.name().equals(availability))
                    return false;
                final Map<UUID, Boolean> joining = new java.util.HashMap<>(snapshot.joiningMembers());
                final Object previous = this.value("server:" + serverId + ":property:admission");
                if (previous instanceof ServerAdmissionSnapshot latest && latest.sampledAt().isAfter(snapshot.sampledAt()))
                    throw new PlacementUnavailableException("Admission snapshot superseded");
                if (previous instanceof ServerAdmissionSnapshot current && !current.isStale(this.now()))
                    current.joiningMembers().forEach(joining::putIfAbsent);
                snapshot.onlineMembers().keySet().forEach(joining::remove);
                final ServerAdmissionSnapshot current = new ServerAdmissionSnapshot(snapshot.onlineMembers(), joining,
                        snapshot.publicCapacity(), snapshot.hardCapacity(), snapshot.sampledAt(), snapshot.validUntil());
                final Map<UUID, Boolean> occupied = this.occupied(serverId, current);
                occupied.put(member, staff); // Destination permission overrides any earlier reservation classification.
                if (!current.fits(occupied.size(), nonStaff(occupied), !staff))
                    return false;
                if (!snapshot.onlineMembers().containsKey(member))
                    joining.put(member, staff);
                this.writeAdmission(serverId, new ServerAdmissionSnapshot(snapshot.onlineMembers(), joining,
                        snapshot.publicCapacity(), snapshot.hardCapacity(), snapshot.sampledAt(), snapshot.validUntil()));
                this.transaction.get().set(sequenceKey(serverId), sequence);
                return true;
            });
    }

    private void writeAdmission(final String serverId, final ServerAdmissionSnapshot snapshot) {
        this.transaction.get().set("server:" + serverId + ":property:admission", snapshot);
    }

    private Set<UUID> staffMembers(final Set<UUID> members) {
        return members.stream().filter(member -> Boolean.TRUE.equals(this.value("admission:staff:" + member)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Map<UUID, Boolean> occupied(final String serverId, final ServerAdmissionSnapshot snapshot) {
        final Map<UUID, Boolean> occupied = new java.util.HashMap<>();
        this.reservations().values().stream().map(RedisServerPlacement::decodeRequired)
                .filter(stored -> stored.reservation().serverId().equals(serverId))
                .filter(stored -> this.transaction.get().leaseNow().isBefore(stored.reservation().expiresAt()))
                .forEach(stored -> stored.reservation().members().forEach(member ->
                        occupied.merge(member, stored.staffMembers().contains(member), (left, right) -> left && right)));
        occupied.putAll(snapshot.joiningMembers());
        occupied.putAll(snapshot.onlineMembers());
        return occupied;
    }

    private static long nonStaff(final Map<UUID, Boolean> members) {
        return members.values().stream().filter(staff -> !staff).count();
    }

    @Override
    public @NotNull EchoFuture<@NotNull Optional<Reservation>> reserve(final @NotNull Request request) {
        return this.async(() -> this.atomic(() -> this.reserveAtomic(request)));
    }

    @Override
    public @NotNull EchoFuture<@NotNull Optional<Reservation>> renew(
            final @NotNull Reservation reservation, final @NotNull Duration lease) {
        if (lease.isNegative() || lease.isZero() || lease.toMillis() == 0)
            throw new IllegalArgumentException("lease must be at least one millisecond");
        return this.async(() -> this.atomic(() -> {
            final Map<String, String> reservations = this.reservations();
            final StoredReservation current = decode(reservations.get(reservation.requestId()));
            if (current == null || !current.reservation().token().equals(reservation.token()))
                return Optional.empty();

            final Reservation renewed = new Reservation(current.reservation().requestId(),
                    current.reservation().token(), current.reservation().serverId(),
                    current.reservation().members(), this.expiresAt(lease));
            reservations.put(renewed.requestId(), encode(new StoredReservation(current.fingerprint(), renewed,
                            current.staffMembers())));
            this.transaction.get().leaseValidUntil(renewed.expiresAt());
            return Optional.of(renewed);
        }));
    }

    @Override
    public @NotNull EchoFuture<@NotNull Boolean> release(final @NotNull Reservation reservation) {
        return this.async(() -> this.atomic(() -> {
            final Map<String, String> reservations = this.reservations();
            final StoredReservation current = decode(reservations.get(reservation.requestId()));
            if (current == null || !current.reservation().token().equals(reservation.token()))
                return false;
            reservations.remove(reservation.requestId());
            return true;
        }));
    }

    @Override
    public @NotNull EchoFuture<@NotNull List<ActiveReservation>> listActiveReservations() {
        return this.async(() -> this.atomic(() -> this.reservations().values().stream()
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
        return this.async(() -> this.atomic(() -> Optional.ofNullable(
                        decode(this.reservations().get(requestId)))
                .map(StoredReservation::reservation)
                .map(RedisServerPlacement::view)));
    }

    @Override
    public @NotNull EchoFuture<@NotNull Optional<ServerStatus>> inspectServer(final @NotNull String serverId) {
        if (Objects.requireNonNull(serverId, "serverId").isBlank())
            throw new IllegalArgumentException("serverId must not be blank");
        return this.async(() -> this.atomic(() -> {
            if (!this.transaction.get().serverIds().contains(serverId))
                return Optional.empty();
            return Optional.of(this.serverStatus(serverId, this.now(),
                    this.reservedByServer().getOrDefault(serverId, 0L)));
        }));
    }

    @Override
    public @NotNull EchoFuture<@NotNull Explanation> explain(final @NotNull Request request) {
        Objects.requireNonNull(request, "request");
        return this.async(() -> this.atomic(() -> this.explainAtomic(request)));
    }

    private Optional<Reservation> reserveAtomic(final Request request) {
        final Map<String, String> reservations = this.reservations();
        final String fingerprint = this.fingerprint(request);
        final StoredReservation existing = decode(reservations.get(request.requestId()));
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint))
                throw new IllegalStateException("Active placement request ID has a different payload: "
                        + request.requestId());
            return Optional.of(existing.reservation());
        }

        final Set<UUID> staff = this.staffMembers(request.members());
        final Explanation explanation = this.explainAtomic(request, staff);
        if (explanation.selectedServerId().isEmpty())
            return Optional.empty();

        final Reservation reservation = new Reservation(request.requestId(), UUID.randomUUID().toString(),
                explanation.selectedServerId().orElseThrow(), request.members(), this.expiresAt(request.lease()));
        reservations.put(request.requestId(), encode(new StoredReservation(fingerprint, reservation,
                        staff)));
        this.transaction.get().leaseValidUntil(reservation.expiresAt());
        return Optional.of(reservation);
    }

    private Explanation explainAtomic(Request request) {
        return this.explainAtomic(request, this.staffMembers(request.members()));
    }

    private Explanation explainAtomic(final Request request, final Set<UUID> staff) {
        final Map<String, Long> reservedByServer = this.reservedByServer();
        final Set<String> registered = this.transaction.get().serverIds();
        final List<String> serverIds = new ArrayList<>(request.candidateServerIds().isEmpty()
                ? registered : request.candidateServerIds());
        serverIds.sort(Comparator.naturalOrder());
        final List<CandidateEvaluation> evaluations = new ArrayList<>(serverIds.size());
        Candidate selected = null;
        final Instant now = this.now();
        for (String serverId : serverIds) {
            final CandidateEvaluation evaluation = registered.contains(serverId)
                    ? this.evaluate(serverId, request, now, reservedByServer.getOrDefault(serverId, 0L), staff)
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

    private CandidateEvaluation evaluate(String serverId, Request request, Instant now, long reserved,
                                         final Set<UUID> staff) {
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
        if (status.admission().isEmpty()
                && this.value("server:" + serverId + ":property:" + PROPERTY_HARD_CAPACITY.key()) != null)
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
        if (this.value("server:" + serverId + ":property:" + PROPERTY_HARD_CAPACITY.key()) != null) {
            final Object value = this.value("server:" + serverId + ":property:admission");
            if (!(value instanceof ServerAdmissionSnapshot admission))
                return rejected(serverId, statusView, RejectionReason.INVALID_LOAD);
            if (admission.isStale(now))
                return rejected(serverId, statusView, RejectionReason.STALE_LOAD);
            if (!Integer.valueOf(admission.hardCapacity()).equals(this.value(
                    "server:" + serverId + ":property:" + PROPERTY_HARD_CAPACITY.key()))
                    || status.capacity().orElseThrow() != admission.publicCapacity())
                return rejected(serverId, statusView, RejectionReason.INVALID_CAPACITY);
            final Map<UUID, Boolean> occupied = this.occupied(serverId, admission);
            final long effective = occupied.size();
            request.members().forEach(member -> occupied.putIfAbsent(member, staff.contains(member)));
            return new CandidateEvaluation(serverId, statusView,
                    admission.fits(occupied.size(), nonStaff(occupied), staff.size() != request.members().size())
                            ? RejectionReason.NONE : RejectionReason.INSUFFICIENT_CAPACITY,
                    OptionalLong.of(effective));
        }
        final long effectiveLoad = Math.addExact(status.participantLoad().orElseThrow(), reserved);
        if (Math.addExact(effectiveLoad, request.members().size()) > status.capacity().orElseThrow())
            return new CandidateEvaluation(serverId, statusView, RejectionReason.INSUFFICIENT_CAPACITY,
                    OptionalLong.of(effectiveLoad));
        return new CandidateEvaluation(serverId, statusView, RejectionReason.NONE,
                OptionalLong.of(effectiveLoad));
    }

    private ServerStatus serverStatus(String serverId, Instant now, long reserved) {
        final boolean heartbeatAlive = this.transaction.get().alive("heartbeat:server:" + serverId);
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
        final Object admissionValue = this.value("server:" + serverId + ":property:admission");
        if (admissionValue instanceof ServerAdmissionSnapshot admission) {
            final Map<UUID, Boolean> occupied = this.occupied(serverId, admission);
            final long hardFree = Math.max(0L, admission.hardCapacity() - occupied.size());
            final long publicFree = Math.max(0L, admission.publicCapacity() - nonStaff(occupied));
            final Map<UUID, Boolean> outstanding = new java.util.HashMap<>(occupied);
            admission.onlineMembers().keySet().forEach(outstanding::remove);
            admission.joiningMembers().keySet().forEach(outstanding::remove);
            return new ServerStatus(serverId, heartbeatAlive, availability, participantLoad,
                    outstanding.size(), capacity, OptionalLong.of(Math.min(hardFree, publicFree)),
                    snapshot != null && !snapshot.isStale(now) && !admission.isStale(now),
                    snapshot != null && snapshot.load().acceptingQueueAssignments(),
                    Optional.of(admission), nonStaff(outstanding), OptionalLong.of(hardFree));
        }
        if (this.value("server:" + serverId + ":property:" + PROPERTY_HARD_CAPACITY.key()) != null)
            return new ServerStatus(serverId, heartbeatAlive, availability, participantLoad, reserved, capacity,
                    OptionalLong.empty(), false, snapshot != null && snapshot.load().acceptingQueueAssignments());
        return new ServerStatus(serverId, heartbeatAlive, availability, participantLoad, reserved, capacity,
                freeSlots, snapshot != null && !snapshot.isStale(now),
                snapshot != null && snapshot.load().acceptingQueueAssignments());
    }

    private Map<String, Long> reservedByServer() {
        final Map<String, Long> reservedByServer = new java.util.HashMap<>();
        this.reservations().values().forEach(value -> {
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
        return this.transaction.get().value(key);
    }

    private Instant expiresAt(Duration lease) {
        return this.transaction.get().leaseNow().plus(lease).truncatedTo(ChronoUnit.MILLIS);
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

    private Map<String, String> reservations() { return this.transaction.get().reservations(); }

    private Instant now() { return this.transaction.get().now(); }

    private <T> T atomic(Supplier<T> action) { return this.atomic(1000, action); }

    private <T> T atomic(long budgetMillis, Supplier<T> action) {
        return this.store.execute(Duration.ofMillis(budgetMillis), state -> {
            if (this.transaction.get() != null)
                throw new IllegalStateException("Nested placement transaction");
            this.transaction.set(state);
            try {
                state.reservations().entrySet().removeIf(entry -> {
                    final Instant expires = decodeRequired(entry.getValue()).reservation().expiresAt();
                    if (!state.leaseNow().isBefore(expires)) return true;
                    state.leaseValidUntil(expires);
                    return false;
                });
                return action.get();
            } finally {
                this.transaction.remove();
            }
        });
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
                Long.toString(reservation.expiresAt().toEpochMilli()), members,
                stored.staffMembers().stream().map(UUID::toString).sorted()
                        .collect(java.util.stream.Collectors.joining(",")));
    }

    private static StoredReservation decode(String value) {
        return value == null ? null : decodeRequired(value);
    }

    private static StoredReservation decodeRequired(String value) {
        final String[] fields = value.split("\n", -1);
        if (fields.length != 6 && fields.length != 7)
            throw new IllegalStateException("Corrupt placement reservation");
        final Set<UUID> members = new LinkedHashSet<>();
        for (String member : fields[5].split(","))
            members.add(UUID.fromString(member));
        final Reservation reservation = new Reservation(
                new String(Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8), fields[2],
                new String(Base64.getUrlDecoder().decode(fields[3]), StandardCharsets.UTF_8),
                members, Instant.ofEpochMilli(Long.parseLong(fields[4])));
        final Set<UUID> staff = fields.length == 7 && !fields[6].isEmpty()
                ? java.util.Arrays.stream(fields[6].split(",")).map(UUID::fromString)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()) : Set.of();
        if (!members.containsAll(staff))
            throw new IllegalStateException("Reservation staff must be members");
        return new StoredReservation(fields[0], reservation, staff);
    }

    private static ActiveReservation view(Reservation reservation) {
        return new ActiveReservation(reservation.requestId(), reservation.serverId(),
                reservation.members(), reservation.expiresAt());
    }

    private record Candidate(String serverId, long effectiveLoad) {
    }

    private record StoredReservation(String fingerprint, Reservation reservation, Set<UUID> staffMembers) {
    }
}
