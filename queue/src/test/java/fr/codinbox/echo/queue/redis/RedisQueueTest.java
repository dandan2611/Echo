package fr.codinbox.echo.queue.redis;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.ondemand.OnDemandServers;
import fr.codinbox.echo.ondemand.ServerHandle;
import fr.codinbox.echo.queue.QueueAdministration;
import fr.codinbox.echo.queue.QueueDefinition;
import fr.codinbox.echo.queue.QueueId;
import fr.codinbox.echo.queue.QueueOptions;
import fr.codinbox.echo.queue.QueueRequest;
import fr.codinbox.echo.queue.QueueRequestStatus;
import fr.codinbox.echo.queue.QueueService;
import fr.codinbox.echo.queue.messaging.QueuePlacementPrepareRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("unit")
class RedisQueueTest {

    private static final QueueId QUEUE_ID = new QueueId("survival:classic");
    private static final QueueDefinition DEFINITION = new QueueDefinition(QUEUE_ID, "survival",
            Map.of(new PropertyKey<>("mode"), "classic"), ServerPlacement.Policy.FILL_MOST_LOADED);
    private static final QueueOptions OPTIONS = new QueueOptions(Duration.ofHours(1), Duration.ofSeconds(30),
            Duration.ofHours(3), Duration.ofSeconds(5), Duration.ofHours(2), Duration.ofSeconds(1));

    @Test
    void lifecycleAndRequestBoundariesFailBeforeQueueWorkStarts() {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(UUID.randomUUID()));

        assertThatThrownBy(QueueService::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("QueueService is not loaded");

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement, List.of(DEFINITION), OPTIONS,
                Clock.systemUTC())) {
            assertThat(queue.enqueue(request).join().state()).isEqualTo(QueueRequestStatus.State.QUEUED);
            assertThatThrownBy(() -> queue.get(QUEUE_ID, " ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> queue.cancel(QUEUE_ID, " ")).isInstanceOf(IllegalArgumentException.class);
            QueueId unknown = new QueueId("unknown");
            assertThatThrownBy(() -> queue.get(unknown, "ticket-1")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> queue.cancel(unknown, "ticket-1")).isInstanceOf(IllegalArgumentException.class);
            queue.start().join();
            queue.start().join();
            assertThat(QueueService.load()).isSameAs(queue);
        }

        assertThatThrownBy(QueueService::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("QueueService is not loaded");

        RedisQueue unopened = new RedisQueue(new InMemoryQueueStore(), echo, onDemand, placement,
                List.of(DEFINITION), OPTIONS, Clock.systemUTC());
        unopened.close();
        assertThatThrownBy(() -> new RedisQueue(new InMemoryQueueStore(), echo, onDemand, placement,
                List.of(DEFINITION, DEFINITION), OPTIONS, Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisQueue(new InMemoryQueueStore(), echo, onDemand, placement,
                List.of(), OPTIONS, Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);

    }

    @Test
    void administrationControlsQueuesThroughThePublicServiceSeam() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-04T12:00:00Z"));
        InMemoryQueueStore store = new InMemoryQueueStore(clock);
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(UUID.randomUUID()));

        try (RedisQueue queue = new RedisQueue(store, mock(EchoClient.class), mock(OnDemandServers.class),
                mock(ServerPlacement.class), List.of(DEFINITION), OPTIONS, clock)) {
            QueueAdministration administration = queue.administration();

            assertThat(administration.pause(QUEUE_ID, "maintenance").join()).isTrue();
            assertThat(administration.pause(QUEUE_ID, "maintenance").join()).isFalse();
            assertThatThrownBy(() -> administration.pause(QUEUE_ID, " "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(queue.enqueue(request).join().state()).isEqualTo(QueueRequestStatus.State.QUEUED);
            administration.wake(QUEUE_ID).join();

            assertThat(administration.listQueues().join()).singleElement().satisfies(overview -> {
                assertThat(overview.definition()).isEqualTo(DEFINITION);
                assertThat(overview.paused()).isTrue();
                assertThat(overview.pauseReason()).isEqualTo("maintenance");
                assertThat(overview.counts()).containsEntry(QueueRequestStatus.State.QUEUED, 1L)
                        .containsEntry(QueueRequestStatus.State.FAILED, 0L);
                assertThat(overview.runState()).isEqualTo(RunState.ALLOCATING.name());
            });
            assertThat(administration.listTickets(QUEUE_ID).join()).singleElement().satisfies(ticket -> {
                assertThat(ticket.status().request()).isEqualTo(request);
                assertThat(ticket.createdAt()).isEqualTo(clock.instant());
                assertThat(ticket.updatedAt()).isEqualTo(clock.instant());
            });

            assertThat(queue.cancel(QUEUE_ID, request.requestId()).join()).isTrue();
            clock.advance(Duration.ofMinutes(1));
            assertThat(administration.retry(QUEUE_ID, request.requestId()).join()).isTrue();
            assertThat(administration.retry(QUEUE_ID, request.requestId()).join()).isFalse();
            assertThat(queue.get(QUEUE_ID, request.requestId()).join()).get().satisfies(status -> {
                assertThat(status.state()).isEqualTo(QueueRequestStatus.State.QUEUED);
                assertThat(status.version()).isEqualTo(2);
                assertThat(status.placementId()).isNull();
                assertThat(status.serverId()).isNull();
                assertThat(status.responses()).isEmpty();
                assertThat(status.failure()).isNull();
            });

            assertThat(queue.cancel(QUEUE_ID, request.requestId()).join()).isTrue();
            clock.advance(Duration.ofHours(1));
            assertThat(administration.purgeTerminal(QUEUE_ID, Duration.ofMinutes(30)).join()).isOne();
            assertThat(administration.listTickets(QUEUE_ID).join()).isEmpty();
            assertThat(administration.resume(QUEUE_ID).join()).isTrue();
            assertThat(administration.resume(QUEUE_ID).join()).isFalse();
            assertThatThrownBy(() -> administration.purgeTerminal(QUEUE_ID, Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void startingAnotherQueueServiceDoesNotReplaceTheLoadedOne() {
        EchoClient echo = mock(EchoClient.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);

        try (RedisQueue first = new RedisQueue(new InMemoryQueueStore(), echo, onDemand, placement,
                List.of(DEFINITION), OPTIONS, Clock.systemUTC());
             RedisQueue second = new RedisQueue(new InMemoryQueueStore(), echo, onDemand, placement,
                     List.of(DEFINITION), OPTIONS, Clock.systemUTC())) {
            first.start().join();

            assertThatThrownBy(() -> second.start().join())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("A QueueService is already loaded");
            assertThat(QueueService.load()).isSameAs(first);
        }
    }

    @Test
    void asynchronousStoreFailuresReachTheCallerAndClaimFailuresStayInsideThePoller() throws Exception {
        QueueStore store = mock(QueueStore.class);
        EchoClient echo = mock(EchoClient.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(UUID.randomUUID()));
        CompletableFuture<Void> claimAttempted = new CompletableFuture<>();

        when(store.enqueue(DEFINITION, request)).thenThrow(new IllegalStateException("redis unavailable"));
        when(store.claim(eq(DEFINITION), anyString(), eq(OPTIONS.claimTtl()))).thenAnswer(ignored -> {
            claimAttempted.complete(null);
            throw new IllegalStateException("redis unavailable");
        });

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement, List.of(DEFINITION), OPTIONS,
                Clock.systemUTC())) {
            assertThatThrownBy(() -> queue.enqueue(request).join())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("redis unavailable");
            queue.start().join();
            claimAttempted.get(5, TimeUnit.SECONDS);
        }

        verify(store, never()).release(any());
    }

    @Test
    void shutdownStopsAClaimThatWasStillBeingAcquired() throws Exception {
        QueueStore store = mock(QueueStore.class);
        CompletableFuture<Void> claimEntered = new CompletableFuture<>();
        CompletableFuture<Void> allowClaim = new CompletableFuture<>();
        CompletableFuture<Void> released = new CompletableFuture<>();
        QueueClaim claim = new QueueClaim(QUEUE_ID, "token", RunRecord.allocating(), List.of());
        when(store.claim(eq(DEFINITION), anyString(), eq(OPTIONS.claimTtl()))).thenAnswer(ignored -> {
            claimEntered.complete(null);
            allowClaim.join();
            return Optional.of(claim);
        });
        doAnswer(ignored -> {
            released.complete(null);
            return null;
        }).when(store).release(claim);
        RedisQueue queue = new RedisQueue(store, mock(EchoClient.class), mock(OnDemandServers.class),
                mock(ServerPlacement.class), List.of(DEFINITION), OPTIONS, Clock.systemUTC());

        queue.start().join();
        claimEntered.get(5, TimeUnit.SECONDS);
        queue.close();
        allowClaim.complete(null);
        released.get(5, TimeUnit.SECONDS);

        verify(store, never()).commit(any(), any(), any());
    }

    @Test
    void workerDoesNotProcessAClaimedQueueAfterItIsPaused() throws Exception {
        QueueStore store = mock(QueueStore.class);
        QueueClaim claim = new QueueClaim(QUEUE_ID, "token", RunRecord.allocating(), List.of());
        CompletableFuture<Void> released = new CompletableFuture<>();
        when(store.claim(eq(DEFINITION), anyString(), eq(OPTIONS.claimTtl()))).thenReturn(Optional.of(claim));
        when(store.paused(QUEUE_ID)).thenReturn(true);
        doAnswer(ignored -> {
            released.complete(null);
            return null;
        }).when(store).release(claim);
        OnDemandServers onDemand = mock(OnDemandServers.class);

        try (RedisQueue queue = new RedisQueue(store, mock(EchoClient.class), onDemand,
                mock(ServerPlacement.class), List.of(DEFINITION), OPTIONS, Clock.systemUTC())) {
            queue.start().join();
            released.get(5, TimeUnit.SECONDS);
        }

        verify(store, never()).commit(any(), any(), any());
        verifyNoInteractions(onDemand);
    }

    @Test
    void longExternalOperationRenewsItsQueueClaim() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        OnDemandServers onDemand = mock(OnDemandServers.class);
        CompletableFuture<ServerHandle> allocation = new CompletableFuture<>();
        CompletableFuture<Void> allocationStarted = new CompletableFuture<>();
        when(onDemand.acquire(any())).thenAnswer(ignored -> {
            allocationStarted.complete(null);
            return allocation;
        });
        QueueOptions shortClaim = new QueueOptions(Duration.ofHours(1), Duration.ofMillis(3),
                Duration.ofHours(3), Duration.ofSeconds(5), Duration.ofHours(2), Duration.ofSeconds(1));
        RedisQueue queue = new RedisQueue(store, mock(EchoClient.class), onDemand,
                mock(ServerPlacement.class), List.of(DEFINITION), shortClaim, Clock.systemUTC());

        queue.start().join();
        queue.enqueue(new QueueRequest("ticket-1", QUEUE_ID, Set.of(UUID.randomUUID()))).join();
        allocationStarted.get(5, TimeUnit.SECONDS);
        store.awaitRenewal();
        queue.close();
        allocation.complete(new ServerHandle("game-1"));
        store.awaitRelease();

        assertThat(store.renewalCount()).isPositive();
    }

    @Test
    void enqueuePersistsEachHandoffBoundaryBeforeTheExternalEffect() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        MessagingProvider messaging = mock(MessagingProvider.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        User user = mock(User.class);
        UUID member = UUID.randomUUID();
        CountDownLatch transferred = new CountDownLatch(1);

        when(echo.getLocalTopic()).thenReturn("server:coordinator");
        when(echo.getMessagingProvider()).thenReturn(messaging);
        when(echo.getUserById(member)).thenReturn(EchoFuture.completed(Optional.of(user)));
        when(user.getCurrentProxyId()).thenReturn(EchoFuture.completed(Optional.of("proxy-1")));
        when(onDemand.acquire(any())).thenReturn(CompletableFuture.completedFuture(new ServerHandle("game-1")));
        when(placement.reserve(any())).thenAnswer(invocation -> {
            ServerPlacement.Request request = invocation.getArgument(0);
            assertThat(store.status("ticket-1").state()).isEqualTo(QueueRequestStatus.State.QUEUED);
            return EchoFuture.completed(Optional.of(new ServerPlacement.Reservation(request.requestId(), "token",
                    "game-1", request.members(), Instant.now().plusSeconds(30))));
        });
        when(placement.renew(any(), any())).thenAnswer(invocation ->
                EchoFuture.completed(Optional.of(invocation.getArgument(0))));
        when(placement.release(any())).thenReturn(EchoFuture.completed(true));
        when(messaging.request(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            Object request = invocation.getArgument(1);
            if (request instanceof QueuePlacementPrepareRequest prepare) {
                assertThat(store.status("ticket-1").state()).isEqualTo(QueueRequestStatus.State.CLAIMED);
                return EchoFuture.completed(new QueuePlacementPrepareRequest.Response(
                        prepare.getPlacementId(), prepare.getRunVersion(), true, null));
            }
            assertThat(store.status("ticket-1").state()).isEqualTo(QueueRequestStatus.State.TRANSFERRING);
            ServerSwitchRequest transfer = (ServerSwitchRequest) request;
            transferred.countDown();
            return EchoFuture.completed(new ServerSwitchRequest.Response(Map.of(transfer.getUserUuids()[0],
                    new ServerSwitchRequest.PlayerResponse(true,
                            ServerSwitchRequest.ServerSwitchRequestStatus.SUCCESS, null))));
        });

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement, List.of(DEFINITION), OPTIONS,
                Clock.systemUTC())) {
            queue.start().join();
            QueueRequestStatus enqueued = queue.enqueue(
                    new QueueRequest("ticket-1", QUEUE_ID, Set.of(member))).join();

            assertThat(enqueued.state()).isEqualTo(QueueRequestStatus.State.QUEUED);
            assertThat(transferred.await(5, TimeUnit.SECONDS)).isTrue();
            awaitState(store, queue, "ticket-1", QueueRequestStatus.State.COMPLETED);
            store.awaitRelease();
        }

        verify(onDemand).acquire(argThat(request -> request.requestId().startsWith("queue:survival:classic:")));
        verify(onDemand, never()).terminate(any());
        verify(placement, never()).release(any());
    }

    @Test
    void rejectedPaperPreparationTerminatesBeforeTransferAndRequeues() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        MessagingProvider messaging = mock(MessagingProvider.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        UUID member = UUID.randomUUID();
        CountDownLatch terminated = new CountDownLatch(1);

        when(echo.getLocalTopic()).thenReturn("server:coordinator");
        when(echo.getMessagingProvider()).thenReturn(messaging);
        when(onDemand.acquire(any())).thenReturn(CompletableFuture.completedFuture(new ServerHandle("game-1")));
        when(onDemand.terminate(any())).thenAnswer(ignored -> {
            terminated.countDown();
            return CompletableFuture.completedFuture(null);
        });
        when(placement.reserve(any())).thenAnswer(invocation -> {
            ServerPlacement.Request request = invocation.getArgument(0);
            return EchoFuture.completed(Optional.of(new ServerPlacement.Reservation(request.requestId(), "token",
                    "game-1", request.members(), Instant.now().plusSeconds(30))));
        });
        when(placement.renew(any(), any())).thenAnswer(invocation ->
                EchoFuture.completed(Optional.of(invocation.getArgument(0))));
        when(placement.release(any())).thenReturn(EchoFuture.completed(true));
        when(messaging.request(anyString(), any(QueuePlacementPrepareRequest.class),
                eq(QueuePlacementPrepareRequest.Response.class), any())).thenAnswer(invocation -> {
            QueuePlacementPrepareRequest prepare = invocation.getArgument(1);
            return EchoFuture.completed(new QueuePlacementPrepareRequest.Response(
                    prepare.getPlacementId(), prepare.getRunVersion(), false, "not ready"));
        });

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement, List.of(DEFINITION), OPTIONS,
                Clock.systemUTC())) {
            queue.start().join();
            queue.enqueue(new QueueRequest("ticket-1", QUEUE_ID, Set.of(member))).join();

            assertThat(terminated.await(5, TimeUnit.SECONDS)).isTrue();
            awaitState(store, queue, "ticket-1", QueueRequestStatus.State.QUEUED);
        }

        verify(placement).release(any());
        verify(messaging, never()).request(anyString(), any(ServerSwitchRequest.class),
                eq(ServerSwitchRequest.Response.class), any());
    }

    @Test
    void enqueueIsIdempotentAndCancellationOnlyWinsWhileQueued() {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        CompletableFuture<ServerHandle> blockedAllocation = new CompletableFuture<>();
        when(onDemand.acquire(any())).thenReturn(blockedAllocation);
        UUID member = UUID.randomUUID();

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement, List.of(DEFINITION), OPTIONS,
                Clock.systemUTC())) {
            queue.start().join();
            QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(member));

            assertThat(queue.enqueue(request).join()).isEqualTo(queue.enqueue(request).join());
            assertThat(queue.cancel(QUEUE_ID, request.requestId()).join()).isTrue();
            assertThat(queue.cancel(QUEUE_ID, request.requestId()).join()).isFalse();
            assertThat(queue.get(QUEUE_ID, request.requestId()).join()).get()
                    .extracting(QueueRequestStatus::state).isEqualTo(QueueRequestStatus.State.CANCELLED);
        }
    }

    @Test
    void cancellationDuringAllocationTerminatesTheUnusedServer() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        CompletableFuture<ServerHandle> allocation = new CompletableFuture<>();
        CompletableFuture<Void> allocationStarted = new CompletableFuture<>();
        CompletableFuture<Void> terminated = new CompletableFuture<>();
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(UUID.randomUUID()));
        when(onDemand.acquire(any())).thenAnswer(ignored -> {
            allocationStarted.complete(null);
            return allocation;
        });
        when(onDemand.terminate(any())).thenAnswer(ignored -> {
            terminated.complete(null);
            return CompletableFuture.completedFuture(null);
        });

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement, List.of(DEFINITION), OPTIONS,
                Clock.systemUTC())) {
            queue.start().join();
            queue.enqueue(request).join();
            allocationStarted.get(5, TimeUnit.SECONDS);
            assertThat(queue.cancel(QUEUE_ID, request.requestId()).join()).isTrue();
            allocation.complete(new ServerHandle("game-1"));
            terminated.get(5, TimeUnit.SECONDS);
            store.awaitRelease();

            assertThat(queue.get(QUEUE_ID, request.requestId()).join()).get()
                    .extracting(QueueRequestStatus::state).isEqualTo(QueueRequestStatus.State.CANCELLED);
        }

        verifyNoInteractions(placement);
    }

    @Test
    void allocationAndReservationFailuresRecoverWithoutLosingTheQueuedRequest() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        CompletableFuture<Void> terminated = new CompletableFuture<>();
        AtomicInteger allocations = new AtomicInteger();
        AtomicInteger reservations = new AtomicInteger();
        QueueDefinition filteredDefinition = new QueueDefinition(QUEUE_ID, "survival", Map.of(
                new PropertyKey<>("mode"), "classic",
                new PropertyKey<>("availability"), "ready",
                new PropertyKey<>("load"), 0,
                ServerPlacement.PROPERTY_CAPACITY, 20), ServerPlacement.Policy.FILL_MOST_LOADED);
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(UUID.randomUUID()));
        when(onDemand.acquire(any())).thenAnswer(ignored -> allocations.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IllegalStateException("allocator unavailable"))
                : CompletableFuture.completedFuture(new ServerHandle("game-1")));
        when(onDemand.terminate(any())).thenAnswer(ignored -> {
            terminated.complete(null);
            return CompletableFuture.completedFuture(null);
        });
        when(placement.reserve(any())).thenAnswer(invocation -> {
            ServerPlacement.Request placementRequest = invocation.getArgument(0);
            assertThat(placementRequest.exactProperties()).containsOnlyKeys(new PropertyKey<>("mode"));
            if (reservations.incrementAndGet() == 1)
                throw new IllegalStateException("placement unavailable");
            return EchoFuture.completed(Optional.empty());
        });

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement,
                List.of(filteredDefinition), OPTIONS, Clock.systemUTC())) {
            queue.start().join();
            queue.enqueue(request).join();
            store.awaitRelease();

            queue.enqueue(request).join();
            store.awaitRelease();

            queue.enqueue(request).join();
            terminated.get(5, TimeUnit.SECONDS);
            store.awaitRelease();
            assertThat(queue.get(QUEUE_ID, request.requestId()).join()).get()
                    .extracting(QueueRequestStatus::state).isEqualTo(QueueRequestStatus.State.QUEUED);
        }

        assertThat(allocations).hasValue(2);
        assertThat(reservations).hasValue(2);
        verify(placement, never()).release(any());
    }

    @Test
    void expiredReservationAbortsBeforePaperHandoffAndRequeues() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        MessagingProvider messaging = mock(MessagingProvider.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        CompletableFuture<Void> terminated = new CompletableFuture<>();
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(UUID.randomUUID()));
        stubMessaging(echo, messaging);
        when(onDemand.acquire(any())).thenReturn(CompletableFuture.completedFuture(new ServerHandle("game-1")));
        when(onDemand.terminate(any())).thenAnswer(ignored -> {
            terminated.complete(null);
            return CompletableFuture.completedFuture(null);
        });
        when(placement.reserve(any())).thenAnswer(invocation -> EchoFuture.completed(Optional.of(
                reservation(invocation.getArgument(0), "game-1"))));
        when(placement.renew(any(), any())).thenReturn(EchoFuture.completed(Optional.empty()));
        when(placement.release(any())).thenReturn(EchoFuture.completed(true));

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement, List.of(DEFINITION), OPTIONS,
                Clock.systemUTC())) {
            queue.start().join();
            queue.enqueue(request).join();
            terminated.get(5, TimeUnit.SECONDS);
            store.awaitRelease();
            assertThat(queue.get(QUEUE_ID, request.requestId()).join()).get()
                    .extracting(QueueRequestStatus::state).isEqualTo(QueueRequestStatus.State.QUEUED);
        }

        verifyNoInteractions(messaging);
        verify(placement).release(any());
    }

    @Test
    void reservationLostAfterPaperAckAbortsBeforeTransfer() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        MessagingProvider messaging = mock(MessagingProvider.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        AtomicInteger renewals = new AtomicInteger();
        CompletableFuture<Void> terminated = new CompletableFuture<>();
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(UUID.randomUUID()));
        stubMessaging(echo, messaging);
        when(onDemand.acquire(any())).thenReturn(CompletableFuture.completedFuture(new ServerHandle("game-1")));
        when(onDemand.terminate(any())).thenAnswer(ignored -> {
            terminated.complete(null);
            return CompletableFuture.completedFuture(null);
        });
        when(placement.reserve(any())).thenAnswer(invocation -> EchoFuture.completed(Optional.of(
                reservation(invocation.getArgument(0), "game-1"))));
        when(placement.renew(any(), any())).thenAnswer(invocation -> renewals.incrementAndGet() == 1
                ? EchoFuture.completed(Optional.of(invocation.getArgument(0)))
                : EchoFuture.completed(Optional.empty()));
        when(placement.release(any())).thenReturn(EchoFuture.completed(true));
        when(messaging.request(anyString(), any(QueuePlacementPrepareRequest.class),
                eq(QueuePlacementPrepareRequest.Response.class), any())).thenAnswer(invocation -> {
            QueuePlacementPrepareRequest prepare = invocation.getArgument(1);
            return EchoFuture.completed(new QueuePlacementPrepareRequest.Response(
                    prepare.getPlacementId(), prepare.getRunVersion(), true, null));
        });

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement, List.of(DEFINITION), OPTIONS,
                Clock.systemUTC())) {
            queue.start().join();
            queue.enqueue(request).join();
            terminated.get(5, TimeUnit.SECONDS);
            awaitState(store, queue, request.requestId(), QueueRequestStatus.State.QUEUED);
        }

        assertThat(renewals).hasValue(2);
        verify(messaging, never()).request(anyString(), any(ServerSwitchRequest.class),
                eq(ServerSwitchRequest.Response.class), any());
    }

    @Test
    void reservationLostWhilePreparedAbortsBeforeTransfer() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        MessagingProvider messaging = mock(MessagingProvider.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        UUID member = UUID.randomUUID();
        UUID placementId = UUID.randomUUID();
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(member));
        ServerPlacement.Reservation reservation = new ServerPlacement.Reservation(
                "placement-ticket", "token", "game-1", Set.of(member), Instant.now().plusSeconds(30));
        StoredRequest prepared = StoredRequest.queued(request, 0, null).withState(
                QueueRequestStatus.State.PREPARED, placementId, "game-1", Map.of(), null);
        RunRecord run = new RunRecord(placementId, 4, RunState.PREPARED, "game-1", request.requestId(),
                StoredReservation.from(reservation), false, Instant.now().plusSeconds(5).toEpochMilli(),
                0, false, null);
        store.seed(new QueueState(1, 1, Map.of(request.requestId(), prepared), run, false, null));
        stubMessaging(echo, messaging);
        when(placement.renew(any(), any())).thenReturn(EchoFuture.completed(Optional.empty()));
        when(placement.release(any())).thenReturn(EchoFuture.completed(true));
        when(onDemand.terminate(any())).thenReturn(CompletableFuture.completedFuture(null));

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement, List.of(DEFINITION), OPTIONS,
                Clock.systemUTC())) {
            queue.start().join();
            awaitState(store, queue, request.requestId(), QueueRequestStatus.State.QUEUED);
        }

        verify(onDemand).terminate(new ServerHandle(
                "game-1", "queue:" + QUEUE_ID.value() + ":" + placementId));
        verify(messaging, never()).request(anyString(), any(ServerSwitchRequest.class),
                eq(ServerSwitchRequest.Response.class), any());
    }

    @Test
    void preparationFailuresAreRecordedAndRemainRetryable() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        MessagingProvider messaging = mock(MessagingProvider.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        AtomicInteger attempts = new AtomicInteger();
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(UUID.randomUUID()));
        stubMessaging(echo, messaging);
        when(onDemand.acquire(any())).thenReturn(CompletableFuture.completedFuture(new ServerHandle("game-1")));
        when(onDemand.terminate(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(placement.reserve(any())).thenAnswer(invocation -> EchoFuture.completed(Optional.of(
                reservation(invocation.getArgument(0), "game-1"))));
        when(placement.renew(any(), any())).thenAnswer(invocation ->
                EchoFuture.completed(Optional.of(invocation.getArgument(0))));
        when(placement.release(any())).thenReturn(EchoFuture.completed(true));
        when(messaging.request(anyString(), any(QueuePlacementPrepareRequest.class),
                eq(QueuePlacementPrepareRequest.Response.class), any())).thenAnswer(invocation -> {
            QueuePlacementPrepareRequest prepare = invocation.getArgument(1);
            return switch (attempts.incrementAndGet()) {
                case 1 -> EchoFuture.of(CompletableFuture.failedFuture(new IllegalStateException()));
                case 2 -> throw new IllegalStateException("paper unavailable");
                case 3 -> throw new CompletionException((Throwable) null);
                case 4 -> EchoFuture.completed(new QueuePlacementPrepareRequest.Response(
                        UUID.randomUUID(), prepare.getRunVersion(), true, null));
                case 5 -> EchoFuture.completed(new QueuePlacementPrepareRequest.Response(
                        prepare.getPlacementId(), prepare.getRunVersion() + 1, true, null));
                default -> EchoFuture.completed(new QueuePlacementPrepareRequest.Response(
                        prepare.getPlacementId(), prepare.getRunVersion(), false, null));
            };
        });

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement, List.of(DEFINITION), OPTIONS,
                Clock.systemUTC())) {
            queue.start().join();
            queue.enqueue(request).join();
            for (int attempt = 1; attempt <= 6; attempt++) {
                store.awaitRelease();
                assertThat(queue.get(QUEUE_ID, request.requestId()).join()).get()
                        .extracting(QueueRequestStatus::state).isEqualTo(QueueRequestStatus.State.QUEUED);
                if (attempt < 6)
                    queue.enqueue(request).join();
            }
        }

        assertThat(store.abortFailures()).containsExactly(
                "IllegalStateException",
                "paper unavailable",
                "CompletionException",
                "Paper returned a stale preparation response",
                "Paper returned a stale preparation response",
                "Paper rejected assignment");
        verify(onDemand, times(6)).terminate(any());
        verify(messaging, never()).request(anyString(), any(ServerSwitchRequest.class),
                eq(ServerSwitchRequest.Response.class), any());
    }

    @Test
    void reusedServerReservationFailureLeavesTheServerRunning() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        UUID member = UUID.randomUUID();
        QueueRequest request = new QueueRequest("ticket-2", QUEUE_ID, Set.of(member));
        StoredRequest queued = StoredRequest.queued(request, 1, null);
        RunRecord ready = new RunRecord(UUID.randomUUID(), 8, RunState.READY, "game-1", null,
                null, true, 0, 0, false, null);
        store.seed(new QueueState(1, 2, Map.of(request.requestId(), queued), ready, false, null));
        when(placement.reserve(any())).thenReturn(EchoFuture.completed(Optional.empty()));

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement, List.of(DEFINITION), OPTIONS,
                Clock.systemUTC())) {
            queue.start().join();
            store.awaitRelease();
            assertThat(queue.get(QUEUE_ID, request.requestId()).join()).get()
                    .extracting(QueueRequestStatus::state).isEqualTo(QueueRequestStatus.State.QUEUED);
        }

        verifyNoInteractions(onDemand);
    }

    @Test
    void reusedServerPreparationCleanupRetriesWithoutTerminatingTheServer() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        MessagingProvider messaging = mock(MessagingProvider.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        UUID member = UUID.randomUUID();
        UUID placementId = UUID.randomUUID();
        QueueRequest request = new QueueRequest("ticket-2", QUEUE_ID, Set.of(member));
        ServerPlacement.Reservation reservation = new ServerPlacement.Reservation(
                "placement-ticket", "token", "game-1", Set.of(member), Instant.parse("2026-09-03T12:01:00Z"));
        StoredReservation storedReservation = StoredReservation.from(reservation);
        StoredRequest claimed = StoredRequest.queued(request, 1, null).withState(
                QueueRequestStatus.State.CLAIMED, placementId, "game-1", Map.of(), null);
        RunRecord preparing = new RunRecord(placementId, 8, RunState.PREPARING, "game-1", request.requestId(),
                storedReservation, true, Instant.now().plusSeconds(5).toEpochMilli(), 0, false, null);
        store.seed(new QueueState(1, 2, Map.of(request.requestId(), claimed), preparing, false, null));
        stubMessaging(echo, messaging);
        when(placement.renew(any(), any())).thenReturn(EchoFuture.completed(Optional.of(reservation)));
        when(messaging.request(anyString(), any(QueuePlacementPrepareRequest.class),
                eq(QueuePlacementPrepareRequest.Response.class), any())).thenAnswer(invocation -> {
            QueuePlacementPrepareRequest prepare = invocation.getArgument(1);
            return EchoFuture.completed(new QueuePlacementPrepareRequest.Response(
                    prepare.getPlacementId(), prepare.getRunVersion(), false, "not ready"));
        });
        AtomicInteger releases = new AtomicInteger();
        when(placement.release(any())).thenAnswer(ignored -> releases.incrementAndGet() == 1
                ? EchoFuture.of(CompletableFuture.failedFuture(new IllegalStateException("release unavailable")))
                : EchoFuture.completed(true));

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement, List.of(DEFINITION), OPTIONS,
                Clock.systemUTC())) {
            queue.start().join();
            store.awaitRelease();
            assertThat(queue.get(QUEUE_ID, request.requestId()).join()).get()
                    .extracting(QueueRequestStatus::state).isEqualTo(QueueRequestStatus.State.CLAIMED);

            queue.enqueue(request).join();
            awaitState(store, queue, request.requestId(), QueueRequestStatus.State.QUEUED);
        }

        assertThat(releases).hasValue(2);
        verifyNoInteractions(onDemand);
    }

    @Test
    void transientTransferOutcomesReplayUntilEveryMemberHasADefinitiveResult() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        MessagingProvider messaging = mock(MessagingProvider.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        User firstUser = mock(User.class);
        User retryingUser = mock(User.class);
        User noProxyUser = mock(User.class);
        User transientLookupUser = mock(User.class);
        UUID first = UUID.randomUUID();
        UUID retrying = UUID.randomUUID();
        UUID disconnected = UUID.randomUUID();
        UUID noProxy = UUID.randomUUID();
        UUID transientLookup = UUID.randomUUID();
        Set<UUID> members = Set.of(first, retrying, disconnected, noProxy, transientLookup);
        AtomicInteger lookupAttempts = new AtomicInteger();
        AtomicInteger renewals = new AtomicInteger();
        AtomicInteger transfers = new AtomicInteger();
        stubMessaging(echo, messaging);
        when(echo.getUserById(first)).thenReturn(EchoFuture.completed(Optional.of(firstUser)));
        when(echo.getUserById(retrying)).thenReturn(EchoFuture.completed(Optional.of(retryingUser)));
        when(echo.getUserById(disconnected)).thenReturn(EchoFuture.completed(Optional.empty()));
        when(echo.getUserById(noProxy)).thenReturn(EchoFuture.completed(Optional.of(noProxyUser)));
        when(echo.getUserById(transientLookup)).thenAnswer(ignored -> {
            if (lookupAttempts.incrementAndGet() == 1)
                throw new IllegalStateException("lookup unavailable");
            return EchoFuture.completed(Optional.of(transientLookupUser));
        });
        when(firstUser.getCurrentProxyId()).thenReturn(EchoFuture.completed(Optional.of("proxy-1")));
        when(retryingUser.getCurrentProxyId()).thenReturn(EchoFuture.completed(Optional.of("proxy-1")));
        when(noProxyUser.getCurrentProxyId()).thenReturn(EchoFuture.completed(Optional.empty()));
        when(transientLookupUser.getCurrentProxyId()).thenReturn(EchoFuture.completed(Optional.of("proxy-1")));
        when(onDemand.acquire(any())).thenReturn(CompletableFuture.completedFuture(new ServerHandle("game-1")));
        when(placement.reserve(any())).thenAnswer(invocation -> EchoFuture.completed(Optional.of(
                reservation(invocation.getArgument(0), "game-1"))));
        when(placement.renew(any(), any())).thenAnswer(invocation -> {
            if (renewals.incrementAndGet() == 4)
                return EchoFuture.of(CompletableFuture.failedFuture(new IllegalStateException("renewal unavailable")));
            return EchoFuture.completed(Optional.of(invocation.getArgument(0)));
        });
        when(placement.release(any())).thenReturn(EchoFuture.completed(true));
        when(messaging.request(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            if (invocation.getArgument(1) instanceof QueuePlacementPrepareRequest prepare)
                return EchoFuture.completed(new QueuePlacementPrepareRequest.Response(
                        prepare.getPlacementId(), prepare.getRunVersion(), true, null));
            int transfer = transfers.incrementAndGet();
            if (transfer == 1)
                throw new IllegalStateException("proxy unavailable");
            ServerSwitchRequest message = invocation.getArgument(1);
            Map<UUID, ServerSwitchRequest.PlayerResponse> responses = new LinkedHashMap<>();
            for (UUID member : message.getUserUuids()) {
                if (member.equals(first) || member.equals(transientLookup))
                    responses.put(member, playerResponse(true, ServerSwitchRequest.ServerSwitchRequestStatus.SUCCESS));
                if (member.equals(retrying) && transfer > 2) {
                    ServerSwitchRequest.ServerSwitchRequestStatus status = switch (transfer) {
                        case 3 -> ServerSwitchRequest.ServerSwitchRequestStatus.CONNECTION_IN_PROGRESS;
                        case 4 -> ServerSwitchRequest.ServerSwitchRequestStatus.TARGET_SERVER_NOT_REGISTERED;
                        case 5 -> ServerSwitchRequest.ServerSwitchRequestStatus.TIMED_OUT;
                        case 6 -> ServerSwitchRequest.ServerSwitchRequestStatus.INTERNAL_ERROR;
                        case 7 -> ServerSwitchRequest.ServerSwitchRequestStatus.PLAYER_NOT_CONNECTED;
                        default -> ServerSwitchRequest.ServerSwitchRequestStatus.SUCCESS;
                    };
                    responses.put(member, playerResponse(status == ServerSwitchRequest.ServerSwitchRequestStatus.SUCCESS,
                            status));
                }
            }
            return EchoFuture.completed(new ServerSwitchRequest.Response(responses));
        });

        QueueOptions retryOptions = new QueueOptions(Duration.ofMillis(1), Duration.ofSeconds(30),
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(1));
        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement,
                List.of(DEFINITION), retryOptions, Clock.systemUTC())) {
            queue.start().join();
            queue.enqueue(new QueueRequest("ticket-1", QUEUE_ID, members)).join();
            QueueRequestStatus failed = awaitState(store, queue, "ticket-1", QueueRequestStatus.State.FAILED);

            assertThat(failed.responses()).hasSize(5);
            assertThat(failed.responses().get(disconnected).getStatus())
                    .isEqualTo(ServerSwitchRequest.ServerSwitchRequestStatus.PLAYER_NOT_CONNECTED);
            assertThat(failed.responses().get(noProxy).getStatus())
                    .isEqualTo(ServerSwitchRequest.ServerSwitchRequestStatus.PLAYER_NOT_CONNECTED);
            assertThat(failed.responses().get(retrying).getStatus())
                    .isEqualTo(ServerSwitchRequest.ServerSwitchRequestStatus.SUCCESS);
        }

        assertThat(transfers).hasValue(8);
        assertThat(lookupAttempts).hasValue(2);
        verify(onDemand, never()).terminate(any());
    }

    @Test
    void retryableTransferWaitsForTheNextPollBeforeRetrying() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        MessagingProvider messaging = mock(MessagingProvider.class);
        User user = mock(User.class);
        UUID member = UUID.randomUUID();
        UUID placementId = UUID.randomUUID();
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(member));
        StoredRequest transferring = StoredRequest.queued(request, 0, null).withState(
                QueueRequestStatus.State.TRANSFERRING, placementId, "game-1", Map.of(), null);
        RunRecord run = new RunRecord(placementId, 4, RunState.TRANSFERRING, "game-1", request.requestId(),
                null, true, 0, Instant.now().plusSeconds(30).toEpochMilli(), false, null);
        store.seed(new QueueState(1, 1, Map.of(request.requestId(), transferring), run, false, null));
        stubMessaging(echo, messaging);
        when(echo.getUserById(member)).thenReturn(EchoFuture.completed(Optional.of(user)));
        when(user.getCurrentProxyId()).thenReturn(EchoFuture.completed(Optional.of("proxy-1")));
        when(messaging.request(anyString(), any(ServerSwitchRequest.class),
                eq(ServerSwitchRequest.Response.class), any())).thenReturn(EchoFuture.completed(
                        new ServerSwitchRequest.Response(Map.of(member, playerResponse(false,
                                ServerSwitchRequest.ServerSwitchRequestStatus.CONNECTION_IN_PROGRESS)))));

        try (RedisQueue queue = new RedisQueue(store, echo, mock(OnDemandServers.class),
                mock(ServerPlacement.class), List.of(DEFINITION), OPTIONS, Clock.systemUTC())) {
            queue.start().join();
            store.awaitRelease();

            assertThat(store.status(request.requestId()).state())
                    .isEqualTo(QueueRequestStatus.State.TRANSFERRING);
            verify(messaging, times(1)).request(anyString(), any(ServerSwitchRequest.class),
                    eq(ServerSwitchRequest.Response.class), any());
        }
    }

    @Test
    void expiredHandoffReservationIsReacquiredBeforeTransferReplay() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        MessagingProvider messaging = mock(MessagingProvider.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        UUID member = UUID.randomUUID();
        UUID placementId = UUID.randomUUID();
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(member));
        StoredRequest transferring = StoredRequest.queued(request, 0, null).withState(
                QueueRequestStatus.State.TRANSFERRING, placementId, "game-1", Map.of(), null);
        StoredReservation expired = new StoredReservation("placement-ticket", "old-token", "game-1",
                Set.of(member), Instant.now().minusSeconds(1).toEpochMilli());
        RunRecord run = new RunRecord(placementId, 4, RunState.TRANSFERRING, "game-1", request.requestId(),
                expired, true, 0, Instant.now().plusSeconds(30).toEpochMilli(), false, null);
        store.seed(new QueueState(1, 1, Map.of(request.requestId(), transferring), run, false, null));
        stubMessaging(echo, messaging);
        when(placement.renew(any(), any())).thenReturn(EchoFuture.completed(Optional.empty()));
        when(placement.reserve(any())).thenAnswer(invocation -> {
            ServerPlacement.Request replacement = invocation.getArgument(0);
            return EchoFuture.completed(Optional.of(new ServerPlacement.Reservation(replacement.requestId(),
                    "new-token", "game-1", replacement.members(), Instant.now().plusSeconds(30))));
        });

        try (RedisQueue queue = new RedisQueue(store, echo, mock(OnDemandServers.class), placement,
                List.of(DEFINITION), OPTIONS, Clock.systemUTC())) {
            queue.start().join();
            store.awaitRelease();
            assertThat(store.run().reservation().token()).isEqualTo("new-token");
            assertThat(queue.get(QUEUE_ID, request.requestId()).join()).get()
                    .extracting(QueueRequestStatus::state).isEqualTo(QueueRequestStatus.State.TRANSFERRING);
        }

        verify(messaging, never()).request(anyString(), any(ServerSwitchRequest.class),
                eq(ServerSwitchRequest.Response.class), any());
    }

    @Test
    void persistedTransferWithoutReservationCompletesAfterCoordinatorRestart() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        MessagingProvider messaging = mock(MessagingProvider.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        User user = mock(User.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T12:00:00Z"));
        UUID member = UUID.randomUUID();
        UUID placementId = UUID.randomUUID();
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(member));
        StoredRequest transferring = StoredRequest.queued(request, 0, null).withState(
                QueueRequestStatus.State.TRANSFERRING, placementId, "game-1", Map.of(), null);
        RunRecord run = new RunRecord(placementId, 4, RunState.TRANSFERRING, "game-1", request.requestId(),
                null, true, 0, clock.instant().plusSeconds(5).toEpochMilli(), false, null);
        store.seed(new QueueState(1, 1, Map.of(request.requestId(), transferring), run, false, null));
        stubMessaging(echo, messaging);
        when(echo.getUserById(member)).thenReturn(EchoFuture.completed(Optional.of(user)));
        when(user.getCurrentProxyId()).thenReturn(EchoFuture.completed(Optional.of("proxy-1")));
        when(messaging.request(anyString(), any(ServerSwitchRequest.class),
                eq(ServerSwitchRequest.Response.class), any())).thenReturn(EchoFuture.completed(
                        new ServerSwitchRequest.Response(Map.of(member, playerResponse(true,
                                ServerSwitchRequest.ServerSwitchRequestStatus.SUCCESS)))));

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement,
                List.of(DEFINITION), OPTIONS, clock)) {
            queue.start().join();
            awaitState(store, queue, request.requestId(), QueueRequestStatus.State.COMPLETED);
        }

        verifyNoInteractions(onDemand, placement);
    }

    @Test
    void persistedAbortAfterHandoffRequeuesWithoutTerminatingTheServer() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        UUID member = UUID.randomUUID();
        UUID placementId = UUID.randomUUID();
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(member));
        StoredRequest transferring = StoredRequest.queued(request, 0, null).withState(
                QueueRequestStatus.State.TRANSFERRING, placementId, "game-1", Map.of(), null);
        RunRecord aborting = new RunRecord(placementId, 5, RunState.ABORTING, "game-1", request.requestId(),
                null, true, 0, 0, false, "coordinator restarted");
        store.seed(new QueueState(1, 1, Map.of(request.requestId(), transferring), aborting, false, null));

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement, List.of(DEFINITION), OPTIONS,
                Clock.systemUTC())) {
            queue.start().join();
            awaitState(store, queue, request.requestId(), QueueRequestStatus.State.QUEUED);
        }

        verifyNoInteractions(onDemand, placement);
    }

    @Test
    void persistedReservationReleaseRetriesAfterFailure() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        ServerPlacement placement = mock(ServerPlacement.class);
        UUID member = UUID.randomUUID();
        UUID placementId = UUID.randomUUID();
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(member));
        StoredRequest completed = StoredRequest.queued(request, 0, null).withState(
                QueueRequestStatus.State.FAILED, placementId, "game-1", Map.of(member,
                        playerResponse(false, ServerSwitchRequest.ServerSwitchRequestStatus.PLAYER_NOT_CONNECTED)),
                "Player did not transfer");
        StoredReservation reservation = new StoredReservation("reservation", "token", "game-1",
                Set.of(member), Instant.now().plusSeconds(30).toEpochMilli());
        RunRecord releasing = new RunRecord(placementId, 5, RunState.RELEASING, "game-1", request.requestId(),
                reservation, true, 0, 0, false, null);
        store.seed(new QueueState(1, 1, Map.of(request.requestId(), completed), releasing, false, null));
        AtomicInteger releases = new AtomicInteger();
        when(placement.release(any())).thenAnswer(ignored -> releases.incrementAndGet() == 1
                ? EchoFuture.of(CompletableFuture.failedFuture(new IllegalStateException("redis unavailable")))
                : EchoFuture.completed(true));

        try (RedisQueue queue = new RedisQueue(store, mock(EchoClient.class), mock(OnDemandServers.class),
                placement, List.of(DEFINITION), OPTIONS, Clock.systemUTC())) {
            queue.start().join();
            store.awaitRelease();
            assertThat(store.run().state()).isEqualTo(RunState.RELEASING);

            queue.enqueue(request).join();
            store.awaitRelease();
            assertThat(store.run().state()).isEqualTo(RunState.READY);
        }

        assertThat(releases).hasValue(2);
    }

    @Test
    void emptyPersistedAllocationIsClearedWithoutAllocatingAServer() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        store.seed(new QueueState(1, 0, Map.of(), RunRecord.allocating(), false, null));
        OnDemandServers onDemand = mock(OnDemandServers.class);

        try (RedisQueue queue = new RedisQueue(store, mock(EchoClient.class), onDemand,
                mock(ServerPlacement.class), List.of(DEFINITION), OPTIONS, Clock.systemUTC())) {
            queue.start().join();
            store.awaitRelease();
            assertThat(store.run()).isNull();
        }

        verifyNoInteractions(onDemand);
    }

    @Test
    void transferDeadlineIsSharedAcrossProxyRequests() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        MessagingProvider messaging = mock(MessagingProvider.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        User firstUser = mock(User.class);
        User secondUser = mock(User.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T12:00:00Z"));
        QueueOptions options = new QueueOptions(Duration.ofMillis(50), Duration.ofSeconds(30),
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMillis(100), Duration.ofMillis(10));
        AtomicInteger transferRequests = new AtomicInteger();

        when(echo.getLocalTopic()).thenReturn("server:coordinator");
        when(echo.getMessagingProvider()).thenReturn(messaging);
        when(echo.getUserById(first)).thenReturn(EchoFuture.completed(Optional.of(firstUser)));
        when(echo.getUserById(second)).thenReturn(EchoFuture.completed(Optional.of(secondUser)));
        when(firstUser.getCurrentProxyId()).thenReturn(EchoFuture.completed(Optional.of("proxy-1")));
        when(secondUser.getCurrentProxyId()).thenReturn(EchoFuture.completed(Optional.of("proxy-2")));
        when(onDemand.acquire(any())).thenReturn(CompletableFuture.completedFuture(new ServerHandle("game-1")));
        when(placement.reserve(any())).thenAnswer(invocation -> {
            ServerPlacement.Request request = invocation.getArgument(0);
            return EchoFuture.completed(Optional.of(new ServerPlacement.Reservation(request.requestId(), "token",
                    "game-1", request.members(), clock.instant().plusSeconds(30))));
        });
        when(placement.renew(any(), any())).thenAnswer(invocation ->
                EchoFuture.completed(Optional.of(invocation.getArgument(0))));
        when(messaging.request(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            if (invocation.getArgument(1) instanceof QueuePlacementPrepareRequest prepare)
                return EchoFuture.completed(new QueuePlacementPrepareRequest.Response(
                        prepare.getPlacementId(), prepare.getRunVersion(), true, null));
            ServerSwitchRequest transfer = invocation.getArgument(1);
            transferRequests.incrementAndGet();
            clock.advance(Duration.ofMillis(101));
            UUID member = transfer.getUserUuids()[0];
            return EchoFuture.completed(new ServerSwitchRequest.Response(Map.of(member,
                    new ServerSwitchRequest.PlayerResponse(true,
                            ServerSwitchRequest.ServerSwitchRequestStatus.SUCCESS, null))));
        });

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement,
                List.of(DEFINITION), options, clock)) {
            queue.start().join();
            queue.enqueue(new QueueRequest("ticket-1", QUEUE_ID, Set.of(first, second))).join();
            awaitState(store, queue, "ticket-1", QueueRequestStatus.State.FAILED);
        }

        assertThat(transferRequests).hasValue(1);
    }

    @Test
    void transferDeadlineReconcilesCurrentServerBeforeTimingOut() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        User user = mock(User.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T12:00:00Z"));
        UUID member = UUID.randomUUID();
        UUID placementId = UUID.randomUUID();
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(member));
        StoredRequest transferring = StoredRequest.queued(request, 0, null).withState(
                QueueRequestStatus.State.TRANSFERRING, placementId, "game-1", Map.of(member,
                        playerResponse(false, ServerSwitchRequest.ServerSwitchRequestStatus.PLAYER_NOT_CONNECTED)), null);
        RunRecord run = new RunRecord(placementId, 4, RunState.TRANSFERRING, "game-1", request.requestId(),
                null, true, 0, clock.millis(), false, null);
        store.seed(new QueueState(1, 1, Map.of(request.requestId(), transferring), run, false, null));
        when(echo.getUserById(member)).thenReturn(EchoFuture.completed(Optional.of(user)));
        when(user.getCurrentServerId()).thenReturn(EchoFuture.completed(Optional.of("game-1")));

        try (RedisQueue queue = new RedisQueue(store, echo, mock(OnDemandServers.class),
                mock(ServerPlacement.class), List.of(DEFINITION), OPTIONS, clock)) {
            queue.start().join();
            QueueRequestStatus completed = awaitState(
                    store, queue, request.requestId(), QueueRequestStatus.State.COMPLETED);

            assertThat(completed.responses().get(member).getStatus())
                    .isEqualTo(ServerSwitchRequest.ServerSwitchRequestStatus.ALREADY_CONNECTED);
        }

        verify(echo, never()).getMessagingProvider();
    }

    @Test
    void persistedPreparationDeadlineIsNotExtendedAfterRecovery() throws Exception {
        InMemoryQueueStore store = new InMemoryQueueStore();
        EchoClient echo = mock(EchoClient.class);
        MessagingProvider messaging = mock(MessagingProvider.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T12:00:00Z"));
        long deadline = clock.instant().plusSeconds(2).toEpochMilli();
        UUID member = UUID.randomUUID();
        UUID placementId = UUID.randomUUID();
        QueueRequest request = new QueueRequest("ticket-1", QUEUE_ID, Set.of(member));
        ServerPlacement.Reservation reservation = new ServerPlacement.Reservation(
                "placement-ticket", "token", "game-1", Set.of(member), clock.instant().plusSeconds(30));
        StoredRequest claimed = StoredRequest.queued(request, 0, null).withState(
                QueueRequestStatus.State.CLAIMED, placementId, "game-1", Map.of(), null);
        RunRecord preparing = new RunRecord(placementId, 4, RunState.PREPARING, "game-1", request.requestId(),
                StoredReservation.from(reservation), false, deadline, 0, false, null);
        store.seed(new QueueState(1, 1, Map.of(request.requestId(), claimed), preparing, false, null));
        stubMessaging(echo, messaging);
        when(placement.renew(any(), any())).thenReturn(EchoFuture.completed(Optional.of(reservation)));
        when(placement.release(any())).thenReturn(EchoFuture.completed(true));
        when(onDemand.terminate(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(messaging.request(anyString(), any(QueuePlacementPrepareRequest.class),
                eq(QueuePlacementPrepareRequest.Response.class), any())).thenAnswer(invocation -> {
            QueuePlacementPrepareRequest prepare = invocation.getArgument(1);
            assertThat(prepare.getPreparationDeadlineEpochMillis()).isEqualTo(deadline);
            return EchoFuture.completed(new QueuePlacementPrepareRequest.Response(
                    prepare.getPlacementId(), prepare.getRunVersion(), false, "not ready"));
        });

        try (RedisQueue queue = new RedisQueue(store, echo, onDemand, placement,
                List.of(DEFINITION), OPTIONS, clock)) {
            queue.start().join();
            awaitState(store, queue, request.requestId(), QueueRequestStatus.State.QUEUED);
        }

        verify(messaging).request(anyString(), any(QueuePlacementPrepareRequest.class),
                eq(QueuePlacementPrepareRequest.Response.class), argThat(timeout -> timeout.toMillis() <= 2_000));
    }

    private static QueueRequestStatus awaitState(InMemoryQueueStore store, RedisQueue queue, String requestId,
                                                 QueueRequestStatus.State state) throws InterruptedException {
        store.awaitState(requestId, state);
        QueueRequestStatus status = queue.get(QUEUE_ID, requestId).join().orElseThrow();
        assertThat(status.state()).isEqualTo(state);
        return status;
    }

    private static void stubMessaging(EchoClient echo, MessagingProvider messaging) {
        when(echo.getLocalTopic()).thenReturn("server:coordinator");
        when(echo.getMessagingProvider()).thenReturn(messaging);
    }

    private static ServerPlacement.Reservation reservation(ServerPlacement.Request request, String serverId) {
        return new ServerPlacement.Reservation(request.requestId(), "token", serverId, request.members(),
                Instant.parse("2026-09-03T12:01:00Z"));
    }

    private static ServerSwitchRequest.PlayerResponse playerResponse(
            boolean successful, ServerSwitchRequest.ServerSwitchRequestStatus status) {
        return new ServerSwitchRequest.PlayerResponse(successful, status, null);
    }

    private static final class InMemoryQueueStore implements QueueStore {
        private final Map<QueueId, QueueState> states = new LinkedHashMap<>();
        private final List<String> abortFailures = new java.util.ArrayList<>();
        private final Semaphore releases = new Semaphore(0);
        private final Semaphore renewals = new Semaphore(0);
        private final AtomicInteger renewalCount = new AtomicInteger();
        private final Clock clock;
        private String claimToken;

        private InMemoryQueueStore() {
            this(Clock.systemUTC());
        }

        private InMemoryQueueStore(Clock clock) {
            this.clock = clock;
        }

        @Override
        public synchronized QueueRequestStatus enqueue(QueueDefinition definition, QueueRequest request) {
            QueueState state = states.getOrDefault(definition.id(), QueueState.empty());
            StoredRequest existing = state.requests().get(request.requestId());
            if (existing != null) {
                if (!existing.samePayload(request))
                    throw new IllegalStateException("Queue request ID has a different payload: " + request.requestId());
                return existing.toStatus();
            }
            for (StoredRequest active : state.requests().values()) {
                if (!active.terminal() && active.members().stream().anyMatch(request.members()::contains))
                    throw new IllegalStateException("A member already has an active request in this queue");
            }
            StoredRequest added = StoredRequest.queued(request, state.nextSequence(), this.clock.millis());
            Map<String, StoredRequest> requests = new LinkedHashMap<>(state.requests());
            requests.put(request.requestId(), added);
            RunRecord run = state.run() == null ? RunRecord.allocating() : state.run();
            states.put(definition.id(), state.withContent(state.nextSequence() + 1, requests, run));
            this.notifyAll();
            return added.toStatus();
        }

        @Override
        public synchronized Optional<QueueRequestStatus> get(QueueId queueId, String requestId) {
            QueueState state = states.get(queueId);
            return state == null ? Optional.empty() : Optional.ofNullable(state.requests().get(requestId))
                    .map(StoredRequest::toStatus);
        }

        QueueRequestStatus status(String requestId) {
            return get(QUEUE_ID, requestId).orElseThrow();
        }

        @Override
        public synchronized boolean cancel(QueueId queueId, String requestId) {
            QueueState state = states.get(queueId);
            if (state == null || !state.requests().containsKey(requestId)
                    || state.requests().get(requestId).state() != QueueRequestStatus.State.QUEUED)
                return false;
            StoredRequest cancelled = state.requests().get(requestId).withState(
                    QueueRequestStatus.State.CANCELLED, null, null, Map.of(), null)
                    .withUpdatedAt(this.clock.millis());
            Map<String, StoredRequest> requests = new LinkedHashMap<>(state.requests());
            requests.put(requestId, cancelled);
            states.put(queueId, state.withContent(state.nextSequence(), requests, state.run()));
            this.notifyAll();
            return true;
        }

        @Override
        public synchronized QueueSnapshot snapshot(QueueId queueId) {
            QueueState state = states.getOrDefault(queueId, QueueState.empty());
            return new QueueSnapshot(state.paused(), state.pauseReason(),
                    List.copyOf(state.requests().values()), state.run());
        }

        @Override
        public synchronized boolean paused(QueueId queueId) {
            return states.getOrDefault(queueId, QueueState.empty()).paused();
        }

        @Override
        public synchronized boolean pause(QueueId queueId, String reason) {
            QueueState state = states.getOrDefault(queueId, QueueState.empty());
            if (state.paused() && reason.equals(state.pauseReason()))
                return false;
            states.put(queueId, state.withPause(true, reason));
            return true;
        }

        @Override
        public synchronized boolean resume(QueueId queueId) {
            QueueState state = states.getOrDefault(queueId, QueueState.empty());
            if (!state.paused())
                return false;
            states.put(queueId, state.withPause(false, null));
            return true;
        }

        @Override
        public synchronized boolean retry(QueueId queueId, String requestId) {
            QueueState state = states.getOrDefault(queueId, QueueState.empty());
            StoredRequest request = state.requests().get(requestId);
            if (request == null || request.state() != QueueRequestStatus.State.FAILED
                    && request.state() != QueueRequestStatus.State.CANCELLED)
                return false;
            if (state.run() != null && requestId.equals(state.run().activeRequestId()))
                return false;
            for (StoredRequest active : state.requests().values()) {
                if (active != request && !active.terminal()
                        && active.members().stream().anyMatch(request.members()::contains))
                    throw new IllegalStateException("A member already has an active request in this queue");
            }
            Map<String, StoredRequest> requests = new LinkedHashMap<>(state.requests());
            requests.put(requestId, request.requeued(state.nextSequence(), this.clock.millis()));
            RunRecord run = state.run() == null ? RunRecord.allocating() : state.run();
            states.put(queueId, state.withContent(state.nextSequence() + 1, requests, run));
            this.notifyAll();
            return true;
        }

        @Override
        public synchronized int purgeTerminal(QueueId queueId, Instant cutoff) {
            QueueState state = states.getOrDefault(queueId, QueueState.empty());
            String activeRequestId = state.run() == null ? null : state.run().activeRequestId();
            Map<String, StoredRequest> requests = new LinkedHashMap<>(state.requests());
            int before = requests.size();
            requests.values().removeIf(request -> request.terminal()
                    && request.updatedAtEpochMillis() != null
                    && request.updatedAtEpochMillis() < cutoff.toEpochMilli()
                    && !request.requestId().equals(activeRequestId));
            if (requests.size() != before)
                states.put(queueId, state.withContent(state.nextSequence(), requests, state.run()));
            return before - requests.size();
        }

        @Override
        public synchronized Optional<QueueClaim> claim(QueueDefinition definition, String workerId, Duration ttl) {
            if (claimToken != null)
                return Optional.empty();
            QueueState state = states.getOrDefault(definition.id(), QueueState.empty());
            if (state.paused())
                return Optional.empty();
            if (state.run() == null && state.requests().values().stream()
                    .noneMatch(request -> request.state() == QueueRequestStatus.State.QUEUED))
                return Optional.empty();
            if (state.run() == null)
                state = state.withContent(state.nextSequence(), state.requests(), RunRecord.allocating());
            states.put(definition.id(), state);
            claimToken = UUID.randomUUID().toString();
            return Optional.of(new QueueClaim(definition.id(), claimToken, state.run(), List.copyOf(state.requests().values())));
        }

        @Override
        public synchronized Optional<QueueClaim> commit(QueueClaim claim, RunRecord next,
                                                         Collection<StoredRequest> updates) {
            QueueState state = states.get(claim.queueId());
            if (!claim.token().equals(claimToken) || state == null || state.run() == null
                    || state.run().revision() != claim.run().revision()
                    || !state.run().placementId().equals(claim.run().placementId()))
                return Optional.empty();
            Map<String, StoredRequest> requests = new LinkedHashMap<>(state.requests());
            updates.forEach(update -> requests.put(update.requestId(), update.withUpdatedAt(this.clock.millis())));
            states.put(claim.queueId(), state.withContent(state.nextSequence(), requests, next));
            if (next != null && next.state() == RunState.ABORTING)
                this.abortFailures.add(next.abortFailure());
            this.notifyAll();
            return Optional.of(new QueueClaim(claim.queueId(), claim.token(), next, List.copyOf(requests.values())));
        }

        @Override
        public synchronized boolean renew(QueueClaim claim, Duration ttl) {
            boolean owned = claim.token().equals(this.claimToken);
            if (owned) {
                this.renewalCount.incrementAndGet();
                this.renewals.release();
            }
            return owned;
        }

        @Override
        public synchronized void release(QueueClaim claim) {
            if (claim.token().equals(claimToken))
                claimToken = null;
            this.releases.release();
        }

        synchronized void seed(QueueState state) {
            this.states.put(QUEUE_ID, state);
            this.notifyAll();
        }

        synchronized RunRecord run() {
            QueueState state = this.states.get(QUEUE_ID);
            return state == null ? null : state.run();
        }

        synchronized List<String> abortFailures() {
            return List.copyOf(this.abortFailures);
        }

        void awaitRelease() throws InterruptedException {
            assertThat(this.releases.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
        }

        void awaitRenewal() throws InterruptedException {
            assertThat(this.renewals.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
        }

        int renewalCount() {
            return this.renewalCount.get();
        }

        synchronized QueueRequestStatus awaitState(String requestId, QueueRequestStatus.State expected)
                throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                QueueState state = this.states.get(QUEUE_ID);
                StoredRequest request = state == null ? null : state.requests().get(requestId);
                if (request != null && request.state() == expected)
                    return request.toStatus();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                    throw new AssertionError("Timed out waiting for " + requestId + " to reach " + expected);
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }

        @Override public void close() { }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        private void advance(Duration duration) {
            this.now.updateAndGet(current -> current.plus(duration));
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return this.now.get(); }
    }
}
