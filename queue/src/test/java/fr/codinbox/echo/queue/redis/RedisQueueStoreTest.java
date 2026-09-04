package fr.codinbox.echo.queue.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import fr.codinbox.echo.queue.QueueDefinition;
import fr.codinbox.echo.queue.QueueId;
import fr.codinbox.echo.queue.QueueRequest;
import fr.codinbox.echo.queue.QueueRequestStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RedisQueueStoreTest {

    private static final QueueId QUEUE_ID = new QueueId("survival:classic");
    private static final QueueDefinition DEFINITION = new QueueDefinition(QUEUE_ID, "survival", Map.of(),
            ServerPlacement.Policy.FILL_MOST_LOADED);
    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    @Mock private RedisConnection connection;
    @Mock private RedissonClient client;
    @Mock private RBucket<String> stateBucket;
    @Mock private RBucket<String> claimBucket;
    @Mock private RLock lock;

    private final AtomicReference<String> state = new AtomicReference<>();
    private final AtomicReference<String> claim = new AtomicReference<>();
    private RedisQueueStore store;

    @BeforeEach
    void setUp() {
        when(connection.getClient()).thenReturn(client);
        when(client.getBucket(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0).endsWith(":state") ? stateBucket : claimBucket);
        lenient().when(client.getLock(anyString())).thenReturn(lock);
        lenient().when(stateBucket.get()).thenAnswer(ignored -> state.get());
        lenient().doAnswer(invocation -> {
            state.set(invocation.getArgument(0));
            return null;
        }).when(stateBucket).set(anyString());
        lenient().when(claimBucket.get()).thenAnswer(ignored -> claim.get());
        lenient().when(claimBucket.trySet(anyString(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenAnswer(invocation ->
                claim.compareAndSet(null, invocation.getArgument(0)));
        lenient().when(claimBucket.delete()).thenAnswer(ignored -> {
            claim.set(null);
            return true;
        });
        this.store = new RedisQueueStore(connection, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void defaultClockConstructorAndCloseRequireNoExtraLifecycle() {
        RedisQueueStore defaultClockStore = new RedisQueueStore(this.connection);

        assertThat(defaultClockStore.paused(QUEUE_ID)).isFalse();
        defaultClockStore.close();
        this.store.close();
    }

    @Test
    void enqueueRoundTripsTheWholeQueueDocumentAndIsIdempotent() {
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(UUID.randomUUID()));

        QueueRequestStatus first = this.store.enqueue(DEFINITION, request);
        QueueRequestStatus replay = this.store.enqueue(DEFINITION, request);

        assertThat(replay).isEqualTo(first);
        assertThat(this.store.get(QUEUE_ID, request.requestId())).contains(first);
        assertThatThrownBy(() -> this.store.enqueue(DEFINITION,
                new QueueRequest(request.requestId(), QUEUE_ID, Set.of(UUID.randomUUID()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different payload");
        assertThat(this.store.snapshot(QUEUE_ID).requests()).singleElement().satisfies(stored -> {
            assertThat(stored.createdAtEpochMillis()).isEqualTo(NOW.toEpochMilli());
            assertThat(stored.updatedAtEpochMillis()).isEqualTo(NOW.toEpochMilli());
        });
    }

    @Test
    void pauseIsBackwardCompatibleIdempotentAndBlocksClaimsButNotEnqueue() {
        this.state.set("{\"formatVersion\":1,\"nextSequence\":0,\"requests\":{},\"run\":null}");

        assertThat(this.store.snapshot(QUEUE_ID).paused()).isFalse();
        assertThat(this.store.paused(QUEUE_ID)).isFalse();
        assertThat(this.store.pause(QUEUE_ID, "maintenance")).isTrue();
        assertThat(this.store.paused(QUEUE_ID)).isTrue();
        assertThat(this.store.pause(QUEUE_ID, "maintenance")).isFalse();
        this.store.enqueue(DEFINITION, request("ticket-1", UUID.randomUUID()));

        assertThat(this.store.snapshot(QUEUE_ID)).satisfies(snapshot -> {
            assertThat(snapshot.paused()).isTrue();
            assertThat(snapshot.pauseReason()).isEqualTo("maintenance");
            assertThat(snapshot.requests()).hasSize(1);
        });
        assertThat(this.store.pause(QUEUE_ID, "deployment")).isTrue();
        assertThat(this.store.pause(QUEUE_ID, "deployment")).isFalse();
        assertThat(this.store.claim(DEFINITION, "worker", Duration.ofSeconds(30))).isEmpty();
        assertThat(this.claim).hasValue(null);
        assertThat(this.store.resume(QUEUE_ID)).isTrue();
        assertThat(this.store.resume(QUEUE_ID)).isFalse();
        QueueClaim claimed = this.store.claim(DEFINITION, "worker", Duration.ofSeconds(30)).orElseThrow();
        this.store.release(claimed);
    }

    @Test
    void retryOnlyRequeuesFailedOrCancelledTicketsAndIncrementsOnce() {
        UUID member = UUID.randomUUID();
        this.store.enqueue(DEFINITION, request("ticket-1", member));
        assertThat(this.store.cancel(QUEUE_ID, "ticket-1")).isTrue();

        assertThat(this.store.retry(QUEUE_ID, "ticket-1")).isTrue();
        assertThat(this.store.retry(QUEUE_ID, "ticket-1")).isFalse();
        assertThat(this.store.get(QUEUE_ID, "ticket-1")).get().satisfies(status -> {
            assertThat(status.version()).isEqualTo(2);
            assertThat(status.state()).isEqualTo(QueueRequestStatus.State.QUEUED);
        });

        transition("ticket-1", QueueRequestStatus.State.FAILED, true, Map.of());
        assertThat(this.store.retry(QUEUE_ID, "ticket-1")).isTrue();
        transition("ticket-1", QueueRequestStatus.State.COMPLETED, true, Map.of());
        assertThat(this.store.retry(QUEUE_ID, "ticket-1")).isFalse();
    }

    @Test
    void purgeUsesTerminalUpdateTimeAndRetainsLegacyOrActiveRunTickets() throws Exception {
        QueueRequest legacyRequest = request("legacy", UUID.randomUUID());
        QueueRequest oldRequest = request("old", UUID.randomUUID());
        StoredRequest legacy = StoredRequest.queued(legacyRequest, 0, null).withState(
                QueueRequestStatus.State.CANCELLED, null, null, Map.of(), null);
        StoredRequest old = StoredRequest.queued(oldRequest, 1, NOW.minus(Duration.ofDays(2)).toEpochMilli())
                .withState(QueueRequestStatus.State.FAILED, UUID.randomUUID(), "game-1", Map.of(), "failed")
                .withUpdatedAt(NOW.minus(Duration.ofDays(1)).toEpochMilli());
        RunRecord releasing = new RunRecord(old.placementId(), 3, RunState.RELEASING, "game-1", old.requestId(),
                null, true, 0, 0, false, null);
        this.state.set(new ObjectMapper().writeValueAsString(new QueueState(
                1, 2, Map.of(legacy.requestId(), legacy, old.requestId(), old), releasing, false, null)));

        assertThat(this.store.retry(QUEUE_ID, old.requestId())).isFalse();
        assertThat(this.store.purgeTerminal(QUEUE_ID, NOW)).isZero();
        this.state.set(new ObjectMapper().writeValueAsString(new QueueState(
                1, 2, Map.of(legacy.requestId(), legacy, old.requestId(), old), null, false, null)));
        assertThat(this.store.purgeTerminal(QUEUE_ID, NOW)).isOne();
        assertThat(this.store.snapshot(QUEUE_ID).requests()).singleElement()
                .extracting(StoredRequest::requestId).isEqualTo("legacy");
    }

    @Test
    void unknownDocumentVersionIsNeverOverwritten() {
        this.state.set("{\"formatVersion\":2,\"nextSequence\":0,\"requests\":{},\"run\":null}");

        assertThatThrownBy(() -> this.store.get(QUEUE_ID, "ticket-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported queue state format: 2");
    }

    @Test
    void expiredClaimFencesAStaleWorkerCommit() {
        this.store.enqueue(DEFINITION, new QueueRequest("ticket-1", QUEUE_ID, Set.of(UUID.randomUUID())));
        QueueClaim claimed = this.store.claim(DEFINITION, "worker-1", Duration.ofSeconds(30)).orElseThrow();
        this.claim.set("worker-2:new-claim");

        Optional<QueueClaim> committed = this.store.commit(claimed,
                claimed.run().next(RunState.READY, "game-1", null, null, false, 0, false, null), Set.of());

        assertThat(committed).isEmpty();
        assertThat(this.store.get(QUEUE_ID, "ticket-1")).get()
                .extracting(QueueRequestStatus::state).isEqualTo(QueueRequestStatus.State.QUEUED);
    }

    @Test
    void onlyTheCurrentOwnerCanRenewAClaim() {
        Duration ttl = Duration.ofSeconds(45);
        when(this.claimBucket.expire(ttl.toMillis(), TimeUnit.MILLISECONDS)).thenReturn(false, true);
        this.store.enqueue(DEFINITION, request("ticket-1", UUID.randomUUID()));
        QueueClaim claimed = this.store.claim(DEFINITION, "worker-1", Duration.ofSeconds(30)).orElseThrow();

        assertThat(this.store.renew(claimed, ttl)).isFalse();
        assertThat(this.store.renew(claimed, ttl)).isTrue();
        this.claim.set("worker-2:new-claim");
        assertThat(this.store.renew(claimed, ttl)).isFalse();
    }

    @Test
    void activeMembersCannotQueueTwiceButTerminalMembersCanQueueAgain() {
        UUID member = UUID.randomUUID();
        this.store.enqueue(DEFINITION, request("ticket-1", member));

        assertThatThrownBy(() -> this.store.enqueue(DEFINITION, request("ticket-2", member)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has an active request");
        assertThat(this.store.enqueue(DEFINITION, request("other-party", UUID.randomUUID())).state())
                .isEqualTo(QueueRequestStatus.State.QUEUED);

        assertThat(this.store.cancel(QUEUE_ID, "missing")).isFalse();
        assertThat(this.store.cancel(QUEUE_ID, "ticket-1")).isTrue();
        assertThat(this.store.cancel(QUEUE_ID, "ticket-1")).isFalse();
        this.store.enqueue(DEFINITION, request("ticket-2", member));
        assertThatThrownBy(() -> this.store.retry(QUEUE_ID, "ticket-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has an active request");
        transition("ticket-2", QueueRequestStatus.State.FAILED, true, Map.of());
        this.store.enqueue(DEFINITION, request("ticket-3", member));
        transition("ticket-3", QueueRequestStatus.State.COMPLETED, true, Map.of());

        assertThat(this.store.enqueue(DEFINITION, request("ticket-4", member)).state())
                .isEqualTo(QueueRequestStatus.State.QUEUED);
    }

    @Test
    void claimContentionAndClearedRunRecoveryPreserveLeaseOwnership() {
        this.store.enqueue(DEFINITION, request("ticket-1", UUID.randomUUID()));
        QueueClaim first = this.store.claim(DEFINITION, "worker-1", Duration.ofSeconds(30)).orElseThrow();

        assertThat(this.store.claim(DEFINITION, "worker-2", Duration.ofSeconds(30))).isEmpty();
        QueueClaim cleared = this.store.commit(first, null, List.of()).orElseThrow();
        this.store.release(cleared);

        QueueClaim recovered = this.store.claim(DEFINITION, "worker-2", Duration.ofSeconds(30)).orElseThrow();
        assertThat(recovered.run().state()).isEqualTo(RunState.ALLOCATING);
        String recoveredToken = this.claim.get();
        this.store.release(first);
        assertThat(this.claim).hasValue(recoveredToken);
        this.store.release(recovered);
        assertThat(this.claim).hasValue(null);
    }

    @Test
    void claimDropsItsLeaseWhenThereIsNoWorkOrOnlyAHistoricalServer() {
        assertThat(this.store.claim(DEFINITION, "worker-1", Duration.ofSeconds(30))).isEmpty();
        assertThat(this.claim).hasValue(null);

        this.store.enqueue(DEFINITION, request("ticket-1", UUID.randomUUID()));
        transition("ticket-1", QueueRequestStatus.State.COMPLETED, true, Map.of());

        assertThat(this.store.claim(DEFINITION, "worker-2", Duration.ofSeconds(30))).isEmpty();
        assertThat(this.claim).hasValue(null);
    }

    @Test
    void freshUnusedServerRemainsClaimableForCleanup() {
        this.store.enqueue(DEFINITION, request("ticket-1", UUID.randomUUID()));
        transition("ticket-1", QueueRequestStatus.State.CANCELLED, false, Map.of());

        QueueClaim cleanup = this.store.claim(DEFINITION, "worker-2", Duration.ofSeconds(30)).orElseThrow();

        assertThat(cleanup.run().state()).isEqualTo(RunState.READY);
        assertThat(cleanup.run().handedOff()).isFalse();
        this.store.release(cleanup);
    }

    @Test
    void commitRejectsStaleRunsAndInvalidVersionJumpsButPersistsValidResponses() {
        UUID member = UUID.randomUUID();
        this.store.enqueue(DEFINITION, request("ticket-1", member));
        QueueClaim claimed = this.store.claim(DEFINITION, "worker-1", Duration.ofSeconds(30)).orElseThrow();
        StoredRequest stored = claimed.requests().getFirst();

        RunRecord newerRevision = claimed.run().next(RunState.READY, "game-1", null,
                null, false, 0, false, null);
        assertThat(this.store.commit(new QueueClaim(QUEUE_ID, claimed.token(), newerRevision, claimed.requests()),
                null, List.of())).isEmpty();
        RunRecord otherPlacement = new RunRecord(UUID.randomUUID(), claimed.run().revision(), claimed.run().state(),
                claimed.run().serverId(), claimed.run().activeRequestId(), claimed.run().reservation(),
                claimed.run().handedOff(), claimed.run().preparationDeadlineEpochMillis(),
                claimed.run().transferDeadlineEpochMillis(),
                claimed.run().terminateOnAbort(), claimed.run().abortFailure());
        assertThat(this.store.commit(new QueueClaim(QUEUE_ID, claimed.token(), otherPlacement, claimed.requests()),
                null, List.of())).isEmpty();

        assertThatThrownBy(() -> this.store.commit(claimed, claimed.run(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run revision");
        StoredRequest missing = StoredRequest.queued(request("missing", UUID.randomUUID()), 10, null)
                .withState(QueueRequestStatus.State.FAILED, null, null, Map.of(), "failed");
        assertThatThrownBy(() -> this.store.commit(claimed, newerRevision, List.of(missing)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request version");
        StoredRequest skippedVersion = stored.withState(QueueRequestStatus.State.CLAIMED,
                        claimed.run().placementId(), "game-1", Map.of(), null)
                .withState(QueueRequestStatus.State.FAILED, claimed.run().placementId(), "game-1", Map.of(), "failed");
        assertThatThrownBy(() -> this.store.commit(claimed, newerRevision, List.of(skippedVersion)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request version");

        ServerSwitchRequest.PlayerResponse response = new ServerSwitchRequest.PlayerResponse(false,
                ServerSwitchRequest.ServerSwitchRequestStatus.SERVER_DISCONNECTED, "maintenance");
        StoredRequest completed = stored.withState(QueueRequestStatus.State.FAILED,
                claimed.run().placementId(), "game-1", Map.of(member, response), "transfer failed");
        QueueClaim committed = this.store.commit(claimed, newerRevision, List.of(completed)).orElseThrow();
        assertThat(this.store.get(QUEUE_ID, "ticket-1")).get()
                .satisfies(status -> {
                    assertThat(status.responses().get(member).getSerializedReason()).isEqualTo("maintenance");
                    assertThat(status.failure()).isEqualTo("transfer failed");
                });

        QueueClaim cleared = this.store.commit(committed, null, List.of()).orElseThrow();
        assertThat(this.store.commit(committed, null, List.of())).isEmpty();
        this.store.release(cleared);
    }

    @Test
    void corruptStateDuringClaimReleasesTheLeaseForRecovery() {
        this.state.set("not-json");

        assertThatThrownBy(() -> this.store.claim(DEFINITION, "worker-1", Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Corrupt queue state");
        assertThat(this.claim).hasValue(null);
    }

    @Test
    void failedRunRecoveryWriteReleasesTheAcquiredClaim() throws Exception {
        QueueRequest request = request("ticket-1", UUID.randomUUID());
        StoredRequest queued = StoredRequest.queued(request, 0, NOW.toEpochMilli());
        this.state.set(new ObjectMapper().writeValueAsString(
                new QueueState(1, 1, Map.of(request.requestId(), queued), null, false, null)));
        doThrow(new IllegalStateException("write failed")).when(this.stateBucket).set(anyString());

        assertThatThrownBy(() -> this.store.claim(DEFINITION, "worker-1", Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("write failed");
        assertThat(this.claim).hasValue(null);
    }

    private QueueClaim transition(String requestId, QueueRequestStatus.State nextState, boolean handedOff,
                                  Map<UUID, ServerSwitchRequest.PlayerResponse> responses) {
        QueueClaim claimed = this.store.claim(DEFINITION, "worker", Duration.ofSeconds(30)).orElseThrow();
        StoredRequest request = claimed.requests().stream()
                .filter(candidate -> candidate.requestId().equals(requestId))
                .findFirst().orElseThrow();
        QueueClaim committed = this.store.commit(claimed,
                claimed.run().next(RunState.READY, "game-1", null, null, handedOff, 0, false, null),
                List.of(request.withState(nextState, claimed.run().placementId(), "game-1", responses, null)))
                .orElseThrow();
        this.store.release(committed);
        return committed;
    }

    private static QueueRequest request(String requestId, UUID member) {
        return new QueueRequest(requestId, QUEUE_ID, Set.of(member));
    }
}
