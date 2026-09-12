package fr.codinbox.echo.core.server.placement;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.server.ServerAvailability;
import fr.codinbox.echo.api.server.ServerLoad;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import fr.codinbox.echo.api.server.ServerAdmissionSnapshot;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RBatch;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("unit")
class RedisServerPlacementTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");
    private static final PropertyKey<String> TYPE = new PropertyKey<>("server_type");

    @Test
    void contendedAdmissionDoesNotUseAnUnboundedLock() throws Exception {
        final Fixture fixture = new Fixture();
        final ServerAdmissionSnapshot snapshot = fixture.seedAdmission(0, 0, 100);
        doThrow(new AssertionError("Unbounded lock acquisition")).when(fixture.redisLock).lock();
        when(fixture.redisLock.tryLock(0, TimeUnit.MILLISECONDS)).thenReturn(false);

        final boolean admitted = fixture.placement.admit("server", UUID.randomUUID(), true, snapshot);

        assertThat(admitted).isFalse();
    }

    @Test
    void staffCanReserveAbovePublicLimitButNonStaffCannot() {
        final Fixture fixture = new Fixture();
        fixture.seedAdmission(100, 100, 100);
        final ServerPlacement.Request staff = request("staff", 1, ServerPlacement.Policy.FILL_MOST_LOADED);
        fixture.placement.publishStaffPermissions(Map.of(staff.members().iterator().next(), true));

        assertThat(fixture.placement.reserve(staff).join()).isPresent();
        assertThat(fixture.placement.reserve(request("public", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join()).isEmpty();
        assertThat(fixture.placement.inspectServer("server").join().orElseThrow().reservedNonStaffSlots()).isZero();
    }

    @Test
    void mixedGroupMustFitBothLimitsIndivisibly() {
        final Fixture fixture = new Fixture();
        fixture.seedAdmission(100, 100, 100);
        final ServerPlacement.Request mixed = request("mixed", 2, ServerPlacement.Policy.FILL_MOST_LOADED);
        fixture.placement.publishStaffPermissions(Map.of(mixed.members().iterator().next(), true));

        assertThat(fixture.placement.reserve(mixed).join()).isEmpty();
        assertThat(fixture.placement.listActiveReservations().join()).isEmpty();
    }

    @Test
    void hardBoundaryAllows120AndRejects121EvenForStaff() {
        final Fixture fixture = new Fixture();
        final ServerAdmissionSnapshot snapshot = fixture.seedAdmission(119, 100, 100);

        assertThat(fixture.placement.admit("server", UUID.randomUUID(), true, snapshot)).isTrue();
        assertThat(fixture.placement.admit("server", UUID.randomUUID(), true, snapshot)).isFalse();
    }

    @Test
    void physicalSpectatorsCountEvenWhenParticipantLoadIsZero() {
        final Fixture fixture = new Fixture();
        fixture.seedAdmission(120, 80, 80);
        final ServerPlacement.Request staff = request("staff", 1, ServerPlacement.Policy.FILL_MOST_LOADED);
        fixture.placement.publishStaffPermissions(Map.of(staff.members().iterator().next(), true));

        assertThat(fixture.placement.reserve(staff).join()).isEmpty();
        assertThat(fixture.placement.inspectServer("server").join().orElseThrow().hardFreeSlots())
                .isEqualTo(OptionalLong.of(0));
    }

    @Test
    void unreservedDirectJoinCannotStealTheLastLeasedSeat() {
        final Fixture fixture = new Fixture();
        final ServerAdmissionSnapshot snapshot = fixture.seedAdmission(119, 99, 100);
        final ServerPlacement.Reservation reserved = fixture.placement.reserve(request("lease", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join().orElseThrow();

        assertThat(fixture.placement.admit("server", UUID.randomUUID(), true, snapshot)).isFalse();
        assertThat(fixture.placement.admit("server", reserved.members().iterator().next(), false, snapshot)).isTrue();
    }

    @Test
    void destinationPermissionOverridesReservedStaffClassification() {
        final Fixture fixture = new Fixture();
        final ServerAdmissionSnapshot snapshot = fixture.seedAdmission(100, 100, 100);
        final ServerPlacement.Request staff = request("staff", 1, ServerPlacement.Policy.FILL_MOST_LOADED);
        final UUID member = staff.members().iterator().next();
        fixture.placement.publishStaffPermissions(Map.of(member, true));
        fixture.placement.reserve(staff).join().orElseThrow();

        assertThat(fixture.placement.admit("server", member, false, snapshot)).isFalse();
        assertThat(fixture.placement.admit("server", member, true, snapshot)).isTrue();
    }

    @Test
    void arrivedMemberIsNotDoubleCountedAgainstTheirReservation() {
        final Fixture fixture = new Fixture();
        fixture.seedAdmission(0, 0, 100);
        final ServerPlacement.Reservation reserved = fixture.placement.reserve(request("lease", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join().orElseThrow();
        fixture.placement.publishAdmission("server", new ServerAdmissionSnapshot(
                Map.of(reserved.members().iterator().next(), false), Map.of(), 100, 120, NOW, NOW.plusSeconds(5)));

        final ServerPlacement.ServerStatus status = fixture.placement.inspectServer("server").join().orElseThrow();

        assertThat(status.reservedSlots()).isZero();
        assertThat(status.admission().orElseThrow().totalCount()).isEqualTo(1);
        assertThat(status.freeSlots()).isEqualTo(OptionalLong.of(99));
    }

    @Test
    void stalePhysicalTelemetryFailsClosedDespiteFreshParticipantLoad() {
        final Fixture fixture = new Fixture();
        fixture.seedAdmission(0, 0, 100);
        fixture.values.put("server:server:property:admission", new ServerAdmissionSnapshot(
                Map.of(), Map.of(), 100, 120, NOW.minusSeconds(5), NOW));

        assertThat(fixture.placement.reserve(request("stale", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join()).isEmpty();
    }

    @Test
    void futurePhysicalTelemetryRejectsReservationsAndDirectAdmission() {
        final Fixture fixture = new Fixture();
        fixture.seedAdmission(0, 0, 100);
        final ServerAdmissionSnapshot future = new ServerAdmissionSnapshot(
                Map.of(), Map.of(), 100, 120, NOW.plusSeconds(1), NOW.plusSeconds(6));
        fixture.placement.publishAdmission("server", future);

        final Optional<ServerPlacement.Reservation> reservation = fixture.placement.reserve(
                request("future", 1, ServerPlacement.Policy.FILL_MOST_LOADED)).join();
        final boolean admitted = fixture.placement.admit("server", UUID.randomUUID(), true, future);

        assertThat(reservation).isEmpty();
        assertThat(admitted).isFalse();
    }

    @Test
    void missingPhysicalTelemetryDoesNotFallBackToParticipants() {
        final Fixture fixture = new Fixture();
        fixture.seedAdmission(0, 0, 100);
        fixture.values.remove("server:server:property:admission");

        assertThat(fixture.placement.reserve(request("missing", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join()).isEmpty();
    }

    @Test
    void expiredStaffSampleDoesNotGrantPublicSurplus() {
        final Fixture fixture = new Fixture();
        fixture.seedAdmission(100, 100, 100);
        final ServerPlacement.Request staff = request("expired", 1, ServerPlacement.Policy.FILL_MOST_LOADED);
        fixture.placement.publishStaffPermissions(Map.of(staff.members().iterator().next(), true));
        fixture.values.remove("admission:staff:" + staff.members().iterator().next());

        assertThat(fixture.placement.reserve(staff).join()).isEmpty();
    }

    @Test
    void concurrentDestinationLoginsShareTheLastPhysicalSeat() {
        final Fixture fixture = new Fixture();
        final ServerAdmissionSnapshot snapshot = fixture.seedAdmission(119, 99, 100);
        final CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() ->
                fixture.placement.admit("server", UUID.randomUUID(), true, snapshot));
        final CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() ->
                fixture.placement.admit("server", UUID.randomUUID(), true, snapshot));

        assertThat(List.of(first.join(), second.join())).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void drainingDestinationRejectsDirectStaffJoin() {
        final Fixture fixture = new Fixture();
        final ServerAdmissionSnapshot snapshot = fixture.seedAdmission(0, 0, 100);
        fixture.values.put("server:server:property:availability", ServerAvailability.DRAINING.name());

        assertThat(fixture.placement.admit("server", UUID.randomUUID(), true, snapshot)).isFalse();
    }

    @Test
    void constructor_usesConnectionClient() {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.getClient()).thenReturn(mock(RedissonClient.class));

        assertThat(new RedisServerPlacement(connection)).isNotNull();
        verify(connection).getClient();
    }

    @Test
    void reserve_filtersCandidatesAndAppliesBothPolicies() {
        Fixture fixture = new Fixture();
        fixture.seed("a", 2, 10, true, NOW.plusSeconds(30), "lobby");
        fixture.seed("b", 4, 10, true, NOW.plusSeconds(30), "lobby");
        fixture.seed("other", 8, 10, true, NOW.plusSeconds(30), "game");

        ServerPlacement.Reservation fill = fixture.placement.reserve(
                request("fill", 2, ServerPlacement.Policy.FILL_MOST_LOADED)).join().orElseThrow();
        assertThat(fill.serverId()).isEqualTo("b");
        fixture.placement.release(fill).join();

        ServerPlacement.Reservation spread = fixture.placement.reserve(
                request("spread", 2, ServerPlacement.Policy.SPREAD_LEAST_LOADED)).join().orElseThrow();
        assertThat(spread.serverId()).isEqualTo("a");
    }

    @Test
    void reserve_appliesCandidateSetAvailabilityAndStableOrdering() {
        Fixture fixture = new Fixture();
        fixture.seed("excluded", 0, 10, true, NOW.plusSeconds(30), "lobby");
        fixture.seed("included", 0, 10, true, NOW.plusSeconds(30), "lobby");
        fixture.values.put("server:included:property:availability", ServerAvailability.ACTIVE.name());

        assertThat(fixture.placement.reserve(request("restricted", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED, Set.of("included"))).join())
                .get().extracting(ServerPlacement.Reservation::serverId).isEqualTo("included");

        Fixture tie = new Fixture();
        tie.seed("a", 3, 10, true, NOW.plusSeconds(30), "lobby");
        tie.seed("b", 3, 10, true, NOW.plusSeconds(30), "lobby");
        assertThat(tie.placement.reserve(request("tie", 1,
                ServerPlacement.Policy.SPREAD_LEAST_LOADED)).join())
                .get().extracting(ServerPlacement.Reservation::serverId).isEqualTo("a");
    }

    @Test
    void reserve_rejectsDrainingMalformedLoadAndInvalidCapacities() {
        Fixture fixture = new Fixture();
        fixture.seed("active", 0, 10, true, NOW.plusSeconds(30), "lobby");
        fixture.values.put("server:active:property:availability", ServerAvailability.ACTIVE.name());
        fixture.seed("draining", 0, 10, true, NOW.plusSeconds(30), "lobby");
        fixture.values.put("server:draining:property:availability", ServerAvailability.DRAINING.name());
        fixture.seed("malformed-load", 0, 10, true, NOW.plusSeconds(30), "lobby");
        fixture.values.put("server:malformed-load:property:load", "not-a-load");
        fixture.seed("malformed-capacity", 0, 10, true, NOW.plusSeconds(30), "lobby");
        fixture.values.put("server:malformed-capacity:property:placement_capacity", "10");
        fixture.seed("zero-capacity", 0, 0, true, NOW.plusSeconds(30), "lobby");

        assertThat(fixture.placement.reserve(request("eligible", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join())
                .get().extracting(ServerPlacement.Reservation::serverId).isEqualTo("active");
    }

    @Test
    void reserve_countsReservationsAtCapacityWithoutIntegerOverflow() {
        Fixture fixture = new Fixture();
        fixture.seed("server", 1, 4, true, NOW.plusSeconds(30), "lobby");

        ServerPlacement.Reservation first = fixture.placement.reserve(request("first", 2,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join().orElseThrow();
        assertThat(fixture.placement.reserve(request("exact", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join()).isPresent();
        assertThat(fixture.placement.reserve(request("over", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join()).isEmpty();

        assertThat(fixture.placement.release(first).join()).isTrue();
        assertThat(fixture.placement.reserve(request("released", 2,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join()).isPresent();

        Fixture overflow = new Fixture();
        overflow.seed("server", Integer.MAX_VALUE, Integer.MAX_VALUE, true,
                NOW.plusSeconds(30), "lobby");
        assertThat(overflow.placement.reserve(request("overflow", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join()).isEmpty();
    }

    @Test
    void reserve_exercisesBothPolicyComparisonOutcomes() {
        Fixture fixture = new Fixture();
        fixture.seed("a", 4, 10, true, NOW.plusSeconds(30), "lobby");
        fixture.seed("b", 2, 10, true, NOW.plusSeconds(30), "lobby");

        ServerPlacement.Reservation fill = fixture.placement.reserve(request("fill-order", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join().orElseThrow();
        assertThat(fill.serverId()).isEqualTo("a");
        fixture.placement.release(fill).join();

        assertThat(fixture.placement.reserve(request("spread-order", 1,
                ServerPlacement.Policy.SPREAD_LEAST_LOADED)).join())
                .get().extracting(ServerPlacement.Reservation::serverId).isEqualTo("b");
    }

    @Test
    void reserve_requiresFreshAcceptingLoadAndEnoughRoomForWholeGroup() {
        Fixture fixture = new Fixture();
        fixture.seed("full", 3, 5, true, NOW.plusSeconds(30), "lobby");
        fixture.seed("stale", 0, 10, true, NOW, "lobby");
        fixture.seed("closed", 0, 10, false, NOW.plusSeconds(30), "lobby");
        fixture.seed("dead", 0, 10, true, NOW.plusSeconds(30), "lobby");
        fixture.ttls.put("heartbeat:server:dead", -2L);

        assertThat(fixture.placement.reserve(request("party", 3,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join()).isEmpty();
        assertThat(fixture.reservationValues).isEmpty();
    }

    @Test
    void reserve_isIdempotentAndRejectsConflictingReplay() {
        Fixture fixture = new Fixture();
        fixture.seed("server", 0, 10, true, NOW.plusSeconds(30), "lobby");
        ServerPlacement.Request request = request("same", 2, ServerPlacement.Policy.FILL_MOST_LOADED);

        ServerPlacement.Reservation reservation = fixture.placement.reserve(request).join().orElseThrow();

        assertThat(fixture.placement.reserve(request).join()).contains(reservation);
        assertThatThrownBy(() -> fixture.placement.reserve(
                request("same", 1, ServerPlacement.Policy.FILL_MOST_LOADED)).join())
                .hasRootCauseMessage("Active placement request ID has a different payload: same");
    }

    @Test
    void renewAndRelease_requireCurrentToken() {
        Fixture fixture = new Fixture();
        fixture.seed("server", 0, 10, true, NOW.plusSeconds(30), "lobby");
        ServerPlacement.Reservation reservation = fixture.placement.reserve(
                request("lease", 1, ServerPlacement.Policy.FILL_MOST_LOADED)).join().orElseThrow();
        ServerPlacement.Reservation stale = new ServerPlacement.Reservation(reservation.requestId(), "stale",
                reservation.serverId(), reservation.members(), reservation.expiresAt());

        assertThat(fixture.placement.renew(stale, Duration.ofMinutes(1)).join()).isEmpty();
        assertThat(fixture.placement.release(stale).join()).isFalse();
        assertThat(fixture.placement.renew(reservation, Duration.ofMinutes(1)).join())
                .get().extracting(ServerPlacement.Reservation::expiresAt)
                .isEqualTo(NOW.plusSeconds(60));
        assertThat(fixture.placement.release(reservation).join()).isTrue();
        assertThat(fixture.placement.release(reservation).join()).isFalse();
        assertThat(fixture.placement.renew(reservation, Duration.ofSeconds(1)).join()).isEmpty();
    }

    @Test
    void renew_rejectsInvalidLeaseDurations() {
        Fixture fixture = new Fixture();
        fixture.seed("server", 0, 10, true, NOW.plusSeconds(30), "lobby");
        ServerPlacement.Reservation reservation = fixture.placement.reserve(request("lease", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join().orElseThrow();

        assertThatThrownBy(() -> fixture.placement.renew(reservation, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.placement.renew(reservation, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.placement.renew(reservation, Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void monitoring_listsFindsAndInspectsWithoutExposingOwnershipTokens() throws Exception {
        Fixture fixture = new Fixture();
        fixture.seed("server", 2, 10, true, NOW.plusSeconds(30), "lobby");
        ServerPlacement.Reservation first = fixture.placement.reserve(request("b-request", 2,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join().orElseThrow();
        fixture.placement.reserve(request("a-request", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join().orElseThrow();
        clearInvocations(fixture.redisLock);

        assertThat(fixture.placement.listActiveReservations().join())
                .extracting(ServerPlacement.ActiveReservation::requestId)
                .containsExactly("a-request", "b-request");
        assertThat(fixture.placement.findActiveReservation("b-request").join())
                .contains(new ServerPlacement.ActiveReservation(first.requestId(), first.serverId(),
                        first.members(), first.expiresAt()));
        assertThat(fixture.placement.findActiveReservation("missing").join()).isEmpty();
        assertThat(ServerPlacement.ActiveReservation.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("token");

        ServerPlacement.ServerStatus status = fixture.placement.inspectServer("server").join().orElseThrow();
        assertThat(status.heartbeatAlive()).isTrue();
        assertThat(status.availability()).isEqualTo(ServerPlacement.AvailabilityState.DEFAULT_ACTIVE);
        assertThat(status.participantLoad()).isEqualTo(OptionalInt.of(2));
        assertThat(status.reservedSlots()).isEqualTo(3);
        assertThat(status.capacity()).isEqualTo(OptionalInt.of(10));
        assertThat(status.freeSlots()).isEqualTo(OptionalLong.of(5));
        assertThat(status.loadFresh()).isTrue();
        assertThat(status.acceptingQueueAssignments()).isTrue();
        assertThat(fixture.placement.inspectServer("missing").join()).isEmpty();
        verify(fixture.redisLock, times(5)).tryLock(1000, TimeUnit.MILLISECONDS);
        verify(fixture.redisLock, times(5)).unlock();
    }

    @Test
    void explain_isReadOnlyReportsRejectionsAndUsesPlacementPolicy() throws Exception {
        Fixture fixture = new Fixture();
        fixture.seed("eligible-low", 2, 10, true, NOW.plusSeconds(30), "lobby");
        fixture.seed("eligible-high", 4, 10, true, NOW.plusSeconds(30), "lobby");
        fixture.seed("dead", 0, 10, true, NOW.plusSeconds(30), "lobby");
        fixture.ttls.put("heartbeat:server:dead", -2L);
        fixture.seed("draining", 0, 10, true, NOW.plusSeconds(30), "lobby");
        fixture.values.put("server:draining:property:availability", ServerAvailability.DRAINING.name());
        fixture.seed("bad-capacity", 0, 10, true, NOW.plusSeconds(30), "lobby");
        fixture.values.put("server:bad-capacity:property:placement_capacity", "10");
        fixture.seed("bad-load", 0, 10, true, NOW.plusSeconds(30), "lobby");
        fixture.values.put("server:bad-load:property:load", "invalid");
        fixture.seed("stale", 0, 10, true, NOW, "lobby");
        fixture.seed("closed", 0, 10, false, NOW.plusSeconds(30), "lobby");
        fixture.seed("mismatch", 0, 10, true, NOW.plusSeconds(30), "game");
        fixture.seed("full", 9, 10, true, NOW.plusSeconds(30), "lobby");
        Set<String> candidates = new java.util.HashSet<>(fixture.serverIds);
        candidates.add("unregistered");

        ServerPlacement.Explanation fill = fixture.placement.explain(request("dry-run", 2,
                ServerPlacement.Policy.FILL_MOST_LOADED, candidates)).join();
        Map<String, ServerPlacement.RejectionReason> reasons = fill.candidates().stream().collect(
                java.util.stream.Collectors.toMap(ServerPlacement.CandidateEvaluation::serverId,
                        ServerPlacement.CandidateEvaluation::rejectionReason));

        assertThat(fill.selectedServerId()).contains("eligible-high");
        assertThat(fill.candidates()).hasSize(candidates.size());
        assertThat(fill.candidates()).extracting(ServerPlacement.CandidateEvaluation::serverId).isSorted();
        assertThat(reasons)
                .containsEntry("eligible-low", ServerPlacement.RejectionReason.NONE)
                .containsEntry("eligible-high", ServerPlacement.RejectionReason.NONE)
                .containsEntry("dead", ServerPlacement.RejectionReason.HEARTBEAT_MISSING)
                .containsEntry("draining", ServerPlacement.RejectionReason.AVAILABILITY_REJECTED)
                .containsEntry("bad-capacity", ServerPlacement.RejectionReason.INVALID_CAPACITY)
                .containsEntry("bad-load", ServerPlacement.RejectionReason.INVALID_LOAD)
                .containsEntry("stale", ServerPlacement.RejectionReason.STALE_LOAD)
                .containsEntry("closed", ServerPlacement.RejectionReason.QUEUE_ASSIGNMENTS_REJECTED)
                .containsEntry("mismatch", ServerPlacement.RejectionReason.PROPERTY_MISMATCH)
                .containsEntry("full", ServerPlacement.RejectionReason.INSUFFICIENT_CAPACITY)
                .containsEntry("unregistered", ServerPlacement.RejectionReason.SERVER_NOT_REGISTERED);
        assertThat(fixture.placement.explain(request("dry-run-spread", 2,
                ServerPlacement.Policy.SPREAD_LEAST_LOADED, candidates)).join().selectedServerId())
                .contains("eligible-low");
        assertThat(fixture.reservationValues).isEmpty();
        verify(fixture.reservations, never()).put(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(fixture.redisLock, times(2)).tryLock(1000, TimeUnit.MILLISECONDS);
        verify(fixture.redisLock, times(2)).unlock();
    }

    @Test
    void reserve_reportsCorruptDataAndEncodingFailuresAndUnlocks() {
        Fixture corrupt = new Fixture();
        corrupt.reservationValues.put("corrupt", "invalid");

        assertThatThrownBy(() -> corrupt.placement.reserve(request("corrupt", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join())
                .hasRootCauseMessage("Corrupt placement reservation");
        verify(corrupt.redisLock).unlock();

        Fixture encoding = new Fixture();
        encoding.seed("server", 0, 10, true, NOW.plusSeconds(30), "lobby");
        when(encoding.client.getConfig()).thenThrow(new IllegalStateException("codec unavailable"));

        assertThatThrownBy(() -> encoding.placement.reserve(request("encoding", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED)).join())
                .hasRootCauseMessage("codec unavailable");
        verify(encoding.redisLock).unlock();
    }

    @Test
    void concurrentWorkersNeverReservePastCapacity() throws Exception {
        Fixture fixture = new Fixture();
        fixture.seed("server", 0, 5, true, NOW.plusSeconds(30), "lobby");
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Optional<ServerPlacement.Reservation>>> attempts = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 16; i++) {
                int number = i;
                attempts.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                    return fixture.placement.reserve(request("worker-" + number, 1,
                            ServerPlacement.Policy.FILL_MOST_LOADED)).join();
                }, executor));
            }
            start.countDown();
            CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).join();
        }

        assertThat(attempts).extracting(CompletableFuture::join)
                .filteredOn(Optional::isPresent).hasSize(5);
    }

    private static ServerPlacement.Request request(String id, int size, ServerPlacement.Policy policy) {
        return request(id, size, policy, Set.of());
    }

    private static ServerPlacement.Request request(
            String id, int size, ServerPlacement.Policy policy, Set<String> candidateServerIds) {
        Set<UUID> members = java.util.stream.IntStream.range(0, size)
                .mapToObj(index -> UUID.nameUUIDFromBytes(
                        (id + ":" + index).getBytes(StandardCharsets.UTF_8)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new ServerPlacement.Request(id, members, candidateServerIds, Map.of(TYPE, "lobby"),
                policy, Duration.ofSeconds(30));
    }

    private static final class Fixture {

        private final RedissonClient client = mock(RedissonClient.class);
        private final RMap<String, Long> servers = mock(RMap.class);
        private final RMapCache<String, String> reservations = mock(RMapCache.class);
        private final RLock redisLock = mock(RLock.class);
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<String, Object> values = new ConcurrentHashMap<>();
        private final Map<String, Long> ttls = new ConcurrentHashMap<>();
        private final Set<String> serverIds = ConcurrentHashMap.newKeySet();
        private final Map<String, String> reservationValues = new ConcurrentHashMap<>();
        private final RedisServerPlacement placement;

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Fixture() {
            Config config = new Config();
            config.setCodec(StringCodec.INSTANCE);
            when(client.getConfig()).thenReturn(config);
            when(client.getMap("servers:map")).thenReturn((RMap) servers);
            when(servers.readAllKeySet()).thenAnswer(ignored -> Set.copyOf(serverIds));
            when(client.getMapCache(RedisServerPlacement.RESERVATIONS_MAP)).thenReturn((RMapCache) reservations);
            when(reservations.get(anyString())).thenAnswer(invocation ->
                    reservationValues.get(invocation.getArgument(0)));
            when(reservations.readAllMap()).thenAnswer(ignored -> Map.copyOf(reservationValues));
            when(reservations.put(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .thenAnswer(invocation -> reservationValues.put(invocation.getArgument(0), invocation.getArgument(1)));
            when(reservations.remove(anyString())).thenAnswer(invocation ->
                    reservationValues.remove(invocation.getArgument(0)));
            when(client.getLock(RedisServerPlacement.PLACEMENT_LOCK)).thenReturn(redisLock);
            try {
                when(redisLock.tryLock(anyLong(), any(TimeUnit.class))).thenAnswer(invocation ->
                        lock.tryLock(invocation.getArgument(0), invocation.getArgument(1)));
            } catch (InterruptedException impossible) {
                throw new AssertionError(impossible);
            }
            doAnswer(ignored -> {
                lock.unlock();
                return null;
            }).when(redisLock).unlock();
            when(client.getBucket(anyString())).thenAnswer(invocation -> bucket(invocation.getArgument(0)));
            final RBatch batch = mock(RBatch.class);
            when(client.createBatch()).thenReturn(batch);
            when(batch.getBucket(anyString())).thenAnswer(invocation -> bucket(invocation.getArgument(0)));
            placement = new RedisServerPlacement(client, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private void seed(String id, int participants, int capacity, boolean accepting,
                          Instant validUntil, String type) {
            serverIds.add(id);
            ttls.put("heartbeat:server:" + id, 30_000L);
            values.put("server:" + id + ":property:placement_capacity", capacity);
            values.put("server:" + id + ":property:load", new ServerLoadSnapshot(
                    new ServerLoad(participants, accepting), NOW.minusSeconds(1), validUntil));
            values.put("server:" + id + ":property:server_type", type);
        }

        private RBucket<Object> bucket(String key) {
            RBucket<Object> bucket = mock(RBucket.class);
            when(bucket.get()).thenAnswer(ignored -> values.get(key));
            when(bucket.remainTimeToLive()).thenAnswer(ignored -> ttls.getOrDefault(key, -2L));
            doAnswer(invocation -> {
                values.put(key, invocation.getArgument(0));
                return null;
            }).when(bucket).set(any());
            when(bucket.setAsync(any(), any(Duration.class))).thenAnswer(invocation -> {
                values.put(key, invocation.getArgument(0));
                return null;
            });
            return bucket;
        }

        private ServerAdmissionSnapshot seedAdmission(final int total, final int nonStaff, final int publicLimit) {
            this.seed("server", 0, publicLimit, true, NOW.plusSeconds(30), "lobby");
            this.values.put("server:server:property:placement_hard_capacity", 120);
            final Map<UUID, Boolean> online = java.util.stream.IntStream.range(0, total).boxed().collect(
                    java.util.stream.Collectors.toMap(index -> new UUID(0, index + 1), index -> index >= nonStaff));
            final ServerAdmissionSnapshot snapshot = new ServerAdmissionSnapshot(online, Map.of(), publicLimit,
                    120, NOW, NOW.plusSeconds(5));
            this.placement.publishAdmission("server", snapshot);
            return snapshot;
        }
    }
}
