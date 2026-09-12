package fr.codinbox.echo.core.server.placement;

import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.server.ServerLoad;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import fr.codinbox.echo.api.server.ServerAdmissionSnapshot;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import fr.codinbox.echo.core.integration.RedisIntegrationTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class RedisServerPlacementIntegrationTest extends RedisIntegrationTestBase {

    private static final PropertyKey<String> TYPE = new PropertyKey<>("server_type");

    @Test
    void heldRedisLockRejectsAdmissionWithoutWaitingForItsOwner() throws Exception {
        final RedisServerPlacement placement = new RedisServerPlacement(mockConnection);
        final ServerAdmissionSnapshot snapshot = this.seedAdmission(placement);
        final org.redisson.api.RLock lock = redissonClient.getLock(RedisServerPlacement.PLACEMENT_LOCK);
        lock.lock();
        try {
            final CompletableFuture<Boolean> admission = CompletableFuture.supplyAsync(() ->
                    placement.admit("server", UUID.randomUUID(), true, snapshot));

            assertThat(admission.get(500, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            lock.unlock();
        }
    }

    @Test
    void permissionPublisherStoresExpiringServerTrustedClassification() {
        final RedisServerPlacement placement = new RedisServerPlacement(mockConnection);
        this.seedAdmission(placement);
        final ServerPlacement.Request request = this.request("staff", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED, Map.of());
        final UUID member = request.members().iterator().next();
        placement.publishStaffPermissions(Map.of(member, true));

        final Optional<ServerPlacement.Reservation> result = placement.reserve(request).join();

        assertThat(result).isPresent();
        assertThat(placement.inspectServer("server").join().orElseThrow().reservedNonStaffSlots()).isZero();
        assertThat(redissonClient.getBucket("admission:staff:" + member).remainTimeToLive()).isPositive();
    }

    @Test
    void destinationAndPlacementShareTheSamePhysicalLease() {
        final RedisServerPlacement placement = new RedisServerPlacement(mockConnection);
        final RedisServerPlacement destination = new RedisServerPlacement(mockConnection);
        final ServerAdmissionSnapshot snapshot = this.seedAdmission(placement);
        final ServerPlacement.Reservation lease = placement.reserve(request("handoff", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED, Map.of())).join().orElseThrow();

        assertThat(destination.admit("server", UUID.randomUUID(), true, snapshot)).isFalse();
        assertThat(destination.admit("server", lease.members().iterator().next(), false, snapshot)).isTrue();
        assertThat(placement.inspectServer("server").join().orElseThrow().reservedSlots()).isZero();
    }

    @Test
    void independentDestinationWorkersCannotBothTakeLastSeat() {
        final RedisServerPlacement firstWorker = new RedisServerPlacement(mockConnection);
        final RedisServerPlacement secondWorker = new RedisServerPlacement(mockConnection);
        final ServerAdmissionSnapshot snapshot = this.seedAdmission(firstWorker);
        final CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() ->
                firstWorker.admit("server", UUID.randomUUID(), true, snapshot));
        final CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() ->
                secondWorker.admit("server", UUID.randomUUID(), true, snapshot));

        assertThat(List.of(first.join(), second.join())).containsExactlyInAnyOrder(true, false);
    }

    private ServerAdmissionSnapshot seedAdmission(final RedisServerPlacement placement) {
        final Instant now = Instant.now();
        this.seed("server", 0, 1, true, now.plusSeconds(60),
                Map.of(ServerPlacement.PROPERTY_HARD_CAPACITY, 2));
        final ServerAdmissionSnapshot snapshot = new ServerAdmissionSnapshot(Map.of(UUID.randomUUID(), true),
                Map.of(), 1, 2, now, now.plusSeconds(60));
        placement.publishAdmission("server", snapshot);
        return snapshot;
    }

    @Test
    void reserve_appliesBothPoliciesExactFiltersAndStableTieBreak() {
        seed("a", 2, 10, true, Instant.now().plusSeconds(60), Map.of(TYPE, "lobby"));
        seed("b", 4, 10, true, Instant.now().plusSeconds(60), Map.of(TYPE, "lobby"));
        seed("ignored", 9, 10, true, Instant.now().plusSeconds(60), Map.of(TYPE, "game"));
        RedisServerPlacement placement = new RedisServerPlacement(mockConnection);

        ServerPlacement.Reservation fill = placement.reserve(request("fill", 2,
                ServerPlacement.Policy.FILL_MOST_LOADED, Map.of(TYPE, "lobby"))).join().orElseThrow();
        assertThat(fill.serverId()).isEqualTo("b");
        assertThat(placement.release(fill).join()).isTrue();

        ServerPlacement.Reservation spread = placement.reserve(request("spread", 2,
                ServerPlacement.Policy.SPREAD_LEAST_LOADED, Map.of(TYPE, "lobby"))).join().orElseThrow();
        assertThat(spread.serverId()).isEqualTo("a");
        assertThat(placement.release(spread).join()).isTrue();

        seed("b", 2, 10, true, Instant.now().plusSeconds(60), Map.of(TYPE, "lobby"));
        assertThat(placement.reserve(request("tie", 1,
                ServerPlacement.Policy.SPREAD_LEAST_LOADED, Map.of(TYPE, "lobby"))).join())
                .get().extracting(ServerPlacement.Reservation::serverId).isEqualTo("a");
    }

    @Test
    void reserve_rejectsSplitStaleAndNonAcceptingCandidates() {
        seed("too-small", 2, 4, true, Instant.now().plusSeconds(60), Map.of(TYPE, "game"));
        seed("stale", 0, 20, true, Instant.now().minusSeconds(1), Map.of(TYPE, "game"));
        seed("closed", 0, 20, false, Instant.now().plusSeconds(60), Map.of(TYPE, "game"));

        Optional<ServerPlacement.Reservation> result = new RedisServerPlacement(mockConnection)
                .reserve(request("party", 3, ServerPlacement.Policy.FILL_MOST_LOADED,
                        Map.of(TYPE, "game"))).join();

        assertThat(result).isEmpty();
        assertThat(redissonClient.getMapCache(RedisServerPlacement.RESERVATIONS_MAP).isEmpty()).isTrue();
    }

    @Test
    void reserve_isIdempotentAndRenewReleaseAreTokenSafe() {
        seed("server", 0, 10, true, Instant.now().plusSeconds(60), Map.of(TYPE, "game"));
        RedisServerPlacement placement = new RedisServerPlacement(mockConnection);
        ServerPlacement.Request request = request("same", 2,
                ServerPlacement.Policy.FILL_MOST_LOADED, Map.of(TYPE, "game"));

        ServerPlacement.Reservation first = placement.reserve(request).join().orElseThrow();
        assertThat(placement.reserve(request).join()).contains(first);
        assertThatThrownBy(() -> placement.reserve(request("same", 1,
                ServerPlacement.Policy.FILL_MOST_LOADED, Map.of(TYPE, "game"))).join())
                .hasRootCauseMessage("Active placement request ID has a different payload: same");

        ServerPlacement.Reservation stale = new ServerPlacement.Reservation(first.requestId(), "stale-token",
                first.serverId(), first.members(), first.expiresAt());
        assertThat(placement.release(stale).join()).isFalse();
        ServerPlacement.Reservation renewed = placement.renew(first, Duration.ofMinutes(1)).join().orElseThrow();
        assertThat(renewed.expiresAt()).isAfter(first.expiresAt());
        assertThat(placement.release(renewed).join()).isTrue();
        assertThat(placement.release(renewed).join()).isFalse();
    }

    @Test
    void concurrentWorkersNeverReserveBeyondCapacity() throws Exception {
        seed("server", 0, 5, true, Instant.now().plusSeconds(60), Map.of(TYPE, "game"));
        RedisServerPlacement firstWorker = new RedisServerPlacement(mockConnection);
        RedisServerPlacement secondWorker = new RedisServerPlacement(mockConnection);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Optional<ServerPlacement.Reservation>>> attempts = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 16; i++) {
                int requestNumber = i;
                RedisServerPlacement worker = i % 2 == 0 ? firstWorker : secondWorker;
                attempts.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                    return worker.reserve(request("worker-" + requestNumber, 1,
                            ServerPlacement.Policy.FILL_MOST_LOADED, Map.of(TYPE, "game"))).join();
                }, executor));
            }
            start.countDown();
            CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).join();
        }

        assertThat(attempts).extracting(CompletableFuture::join)
                .filteredOn(Optional::isPresent).hasSize(5);
    }

    private ServerPlacement.Request request(
            String id, int members, ServerPlacement.Policy policy, Map<PropertyKey<?>, Object> filters) {
        Set<UUID> players = java.util.stream.IntStream.range(0, members)
                .mapToObj(ignored -> UUID.randomUUID())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new ServerPlacement.Request(id, players, Set.of(), filters, policy, Duration.ofSeconds(30));
    }

    private void seed(String id, int participants, int capacity, boolean accepting,
                      Instant validUntil, Map<PropertyKey<?>, Object> properties) {
        redissonClient.<String, Long>getMap("servers:map").put(id, Instant.now().toEpochMilli());
        redissonClient.getBucket("heartbeat:server:" + id).set(1, Duration.ofMinutes(5));
        redissonClient.getBucket("server:" + id + ":property:placement_capacity").set(capacity);
        redissonClient.getBucket("server:" + id + ":property:load").set(new ServerLoadSnapshot(
                new ServerLoad(participants, accepting), validUntil.minusSeconds(1), validUntil));
        properties.forEach((key, value) ->
                redissonClient.getBucket("server:" + id + ":property:" + key.key()).set(value));
    }
}
