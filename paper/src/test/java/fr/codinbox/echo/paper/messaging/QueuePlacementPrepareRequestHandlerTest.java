package fr.codinbox.echo.paper.messaging;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerAvailability;
import fr.codinbox.echo.api.server.ServerLoad;
import fr.codinbox.echo.api.server.ServerLoadManager;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import fr.codinbox.echo.paper.EchoPaper;
import fr.codinbox.echo.queue.QueuePlacementAssignment;
import fr.codinbox.echo.queue.QueuePlacementPreparer;
import fr.codinbox.echo.queue.messaging.QueuePlacementPrepareRequest;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class QueuePlacementPrepareRequestHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");
    private static final UUID PLACEMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID SECOND_MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Mock private EchoPaper plugin;
    @Mock private org.bukkit.Server bukkitServer;
    @Mock private BukkitScheduler scheduler;
    @Mock private EchoClient echo;
    @Mock private Server server;
    @Mock private ServerLoadManager loadManager;
    @Mock private MessagingProvider messaging;
    @Mock private QueuePlacementPreparer preparer;
    @Mock private Logger logger;

    private final AtomicBoolean stopping = new AtomicBoolean();
    private QueuePlacementPrepareRequestHandler handler;

    @BeforeEach
    void setUp() {
        lenient().when(plugin.getServer()).thenReturn(bukkitServer);
        lenient().when(plugin.getLogger()).thenReturn(logger);
        lenient().when(bukkitServer.getScheduler()).thenReturn(scheduler);
        lenient().when(echo.getCurrentResourceId()).thenReturn(Optional.of("game-1"));
        lenient().when(echo.getServerById("game-1")).thenReturn(EchoFuture.completed(Optional.of(server)));
        lenient().when(server.getAvailability()).thenReturn(EchoFuture.completed(ServerAvailability.ACTIVE));
        lenient().when(echo.getServerLoadManager()).thenReturn(loadManager);
        lenient().when(loadManager.getCurrent()).thenReturn(EchoFuture.completed(Optional.of(load(true, NOW.plusSeconds(30)))));
        lenient().when(echo.getMessagingProvider()).thenReturn(messaging);
        lenient().when(echo.getLocalTopic()).thenReturn("server:game-1");
        lenient().when(messaging.publish(eq("reply:coordinator"), any(QueuePlacementPrepareRequest.Response.class)))
                .thenReturn(EchoFuture.completed(null));
        this.handler = handler(() -> preparer);
    }

    @Test
    void publicConstructorAcceptsDependencies() {
        assertThat(new QueuePlacementPrepareRequestHandler(plugin, echo, stopping::get, () -> preparer))
                .isNotNull();
    }

    @Test
    void acceptedPreparationWaitsForReadinessAndPaperSchedulerAndPreservesCorrelation() {
        EchoFuture<Optional<Server>> serverLookup = new EchoFuture<>();
        CompletableFuture<QueuePlacementPreparer.Decision> decision = new CompletableFuture<>();
        when(echo.getServerById("game-1")).thenReturn(serverLookup);
        when(preparer.prepare(any())).thenReturn(decision);
        QueuePlacementPrepareRequest request = request();

        this.handler.onReceive(request);

        verify(scheduler, never()).runTask(eq(plugin), any(Runnable.class));
        verify(preparer, never()).prepare(any());
        verify(messaging, never()).publish(any(), any());

        serverLookup.complete(Optional.of(server));
        Runnable task = scheduledTask();
        verify(preparer, never()).prepare(any());
        task.run();

        ArgumentCaptor<QueuePlacementAssignment> assignment = ArgumentCaptor.forClass(QueuePlacementAssignment.class);
        verify(preparer).prepare(assignment.capture());
        assertThat(assignment.getValue().requests()).isEqualTo(request.getRequests());
        assertThat(assignment.getValue().preparationDeadline()).isEqualTo(NOW.plusSeconds(10));
        verify(messaging, never()).publish(any(), any());

        decision.complete(QueuePlacementPreparer.Decision.accept());
        QueuePlacementPrepareRequest.Response response = response();
        assertCorrelated(response, request);
        assertThat(response.isAccepted()).isTrue();
        assertThat(response.getReason()).isNull();
    }

    @Test
    void expiredAssignmentIsRejectedBeforeReadinessLookup() {
        QueuePlacementPrepareRequest request = request();
        request.setPreparationDeadlineEpochMillis(NOW.toEpochMilli());

        this.handler.onReceive(request);

        verify(echo, never()).getServerById(any());
        verifyNoInteractions(scheduler);
        assertRejected(request, "Queue assignment deadline expired");
    }

    @Test
    void pendingPreparationIsRejectedAtDeadlineWithoutAnotherRequest() {
        MutableClock clock = new MutableClock(NOW);
        this.handler = new QueuePlacementPrepareRequestHandler(
                plugin, echo, stopping::get, () -> preparer, clock);
        when(echo.getServerById("game-1")).thenReturn(new EchoFuture<>());
        QueuePlacementPrepareRequest first = request();
        first.setPreparationDeadlineEpochMillis(NOW.plusMillis(10_001).toEpochMilli());
        QueuePlacementPrepareRequest duplicate = request();
        duplicate.setPreparationDeadlineEpochMillis(first.getPreparationDeadlineEpochMillis());
        duplicate.setMessageId(SECOND_MESSAGE_ID);

        this.handler.onReceive(first);
        this.handler.onReceive(duplicate);

        ArgumentCaptor<Runnable> deadlineTask = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskLater(eq(plugin), deadlineTask.capture(), eq(201L));
        verify(echo).getServerById("game-1");
        verify(messaging, never()).publish(any(), any());

        clock.advanceMillis(10_001);
        deadlineTask.getValue().run();

        List<QueuePlacementPrepareRequest.Response> responses = responses(2);
        assertThat(responses).allSatisfy(response -> {
            assertThat(response.isAccepted()).isFalse();
            assertThat(response.getReason()).isEqualTo("Queue assignment deadline expired");
        });
        assertCorrelated(responseFor(responses, MESSAGE_ID), first);
        assertCorrelated(responseFor(responses, SECOND_MESSAGE_ID), duplicate);
    }

    @Test
    void gameAcceptanceAfterDeadlineIsRejectedAndNotReplayed() {
        MutableClock clock = new MutableClock(NOW);
        this.handler = new QueuePlacementPrepareRequestHandler(
                plugin, echo, stopping::get, () -> preparer, clock);
        CompletableFuture<QueuePlacementPreparer.Decision> decision = new CompletableFuture<>();
        when(preparer.prepare(any())).thenReturn(decision);
        QueuePlacementPrepareRequest first = request();

        this.handler.onReceive(first);
        scheduledTask().run();
        clock.advanceSeconds(11);
        decision.complete(QueuePlacementPreparer.Decision.accept());

        assertRejected(first, "Queue assignment deadline expired");

        QueuePlacementPrepareRequest replay = request();
        replay.setMessageId(SECOND_MESSAGE_ID);
        this.handler.onReceive(replay);

        verify(preparer).prepare(any());
        QueuePlacementPrepareRequest.Response replayResponse = responses(2).stream()
                .filter(response -> SECOND_MESSAGE_ID.equals(response.getMessageId()))
                .findFirst()
                .orElseThrow();
        assertThat(replayResponse.isAccepted()).isFalse();
        assertThat(replayResponse.getReason()).isEqualTo("Queue assignment deadline expired");
    }

    @Test
    void invalidAssignmentReturnsCorrelatedNackWithoutScheduling() {
        QueuePlacementPrepareRequest request = request();
        request.setQueueId(" ");

        this.handler.onReceive(request);

        verifyNoInteractions(scheduler);
        assertRejected(request, "Invalid Queue assignment");
    }

    @Test
    void missingLocalResourceRejectsAssignment() {
        when(echo.getCurrentResourceId()).thenReturn(Optional.empty());
        QueuePlacementPrepareRequest request = request();

        this.handler.onReceive(request);

        verify(scheduler, never()).runTask(eq(plugin), any(Runnable.class));
        assertRejected(request, "Assignment targets another server");
    }

    @Test
    void assignmentForAnotherServerIsRejected() {
        QueuePlacementPrepareRequest request = request();
        request.setServerId("game-2");

        this.handler.onReceive(request);

        verify(scheduler, never()).runTask(eq(plugin), any(Runnable.class));
        assertRejected(request, "Assignment targets another server");
    }

    @Test
    void missingServerIsRejectedOnPaperThread() {
        when(echo.getServerById("game-1")).thenReturn(EchoFuture.completed(Optional.empty()));
        QueuePlacementPrepareRequest request = request();

        receiveAndRun(request);

        assertRejected(request, "Server is not active");
    }

    @Test
    void drainingServerIsRejectedOnPaperThread() {
        when(server.getAvailability()).thenReturn(EchoFuture.completed(ServerAvailability.DRAINING));
        QueuePlacementPrepareRequest request = request();

        receiveAndRun(request);

        assertRejected(request, "Server is not active");
    }

    @Test
    void unavailableLoadIsRejectedOnPaperThread() {
        when(loadManager.getCurrent()).thenReturn(EchoFuture.completed(Optional.empty()));
        QueuePlacementPrepareRequest request = request();

        receiveAndRun(request);

        assertRejected(request, "Server load is unavailable");
    }

    @Test
    void staleLoadRejectsWithoutCallingGamePlugin() {
        when(loadManager.getCurrent()).thenReturn(EchoFuture.completed(Optional.of(load(true, NOW))));
        QueuePlacementPrepareRequest request = request();

        receiveAndRun(request);

        verify(preparer, never()).prepare(any());
        assertRejected(request, "Server load is stale");
    }

    @Test
    void loadThatRejectsQueueAssignmentsReturnsNack() {
        when(loadManager.getCurrent()).thenReturn(EchoFuture.completed(Optional.of(load(false, NOW.plusSeconds(30)))));
        QueuePlacementPrepareRequest request = request();

        receiveAndRun(request);

        verify(preparer, never()).prepare(any());
        assertRejected(request, "Server is not accepting Queue assignments");
    }

    @Test
    void stoppingStateIsRecheckedOnPaperThread() {
        QueuePlacementPrepareRequest request = request();
        this.handler.onReceive(request);
        Runnable task = scheduledTask();

        this.stopping.set(true);
        task.run();

        verify(preparer, never()).prepare(any());
        assertRejected(request, "Server is stopping");
    }

    @Test
    void drainAfterAsyncReadinessBeforePaperTaskRejectsPreparation() {
        EchoFuture<Optional<Server>> serverLookup = new EchoFuture<>();
        when(echo.getServerById("game-1")).thenReturn(serverLookup);
        QueuePlacementPrepareRequest request = request();

        this.handler.onReceive(request);
        serverLookup.complete(Optional.of(server));
        Runnable task = scheduledTask();

        when(plugin.isDraining()).thenReturn(true);
        task.run();

        verify(preparer, never()).prepare(any());
        assertRejected(request, "Server is draining");
    }

    @Test
    void synchronousReadinessProviderFailureReturnsNack() {
        IllegalStateException failure = new IllegalStateException("registry offline");
        when(echo.getServerById("game-1")).thenThrow(failure);
        QueuePlacementPrepareRequest request = request();

        this.handler.onReceive(request);

        verify(scheduler, never()).runTask(eq(plugin), any(Runnable.class));
        assertRejected(request, "Failed to verify server readiness: registry offline");
    }

    @Test
    void asynchronousReadinessProviderFailureIsUnwrappedInNack() {
        EchoFuture<Optional<Server>> failure = failedFuture(
                new CompletionException(new IllegalStateException("availability offline")));
        when(echo.getServerById("game-1")).thenReturn(failure);
        QueuePlacementPrepareRequest request = request();

        this.handler.onReceive(request);

        verify(scheduler, never()).runTask(eq(plugin), any(Runnable.class));
        assertRejected(request, "Failed to verify server readiness: availability offline");
    }

    @Test
    void absentGamePreparerReturnsNack() {
        this.handler = handler(() -> null);
        QueuePlacementPrepareRequest request = request();

        receiveAndRun(request);

        assertRejected(request, "No QueuePlacementPreparer is registered");
    }

    @Test
    void synchronousGamePreparerFailureReturnsNack() {
        this.handler = handler(() -> {
            throw new IllegalStateException();
        });
        QueuePlacementPrepareRequest request = request();

        receiveAndRun(request);

        assertRejected(request, "IllegalStateException");
    }

    @Test
    void duplicateAssignmentSharesFailedPreparationAndCorrelatesBothNacks() {
        CompletableFuture<QueuePlacementPreparer.Decision> decision = new CompletableFuture<>();
        when(preparer.prepare(any())).thenReturn(decision);
        QueuePlacementPrepareRequest first = request();
        QueuePlacementPrepareRequest duplicate = request();
        duplicate.setMessageId(SECOND_MESSAGE_ID);

        this.handler.onReceive(first);
        scheduledTask().run();
        this.handler.onReceive(duplicate);

        verify(scheduler).runTask(eq(plugin), any(Runnable.class));
        verify(preparer).prepare(any());
        verify(messaging, never()).publish(any(), any());

        decision.completeExceptionally(new IllegalStateException("game provider offline"));

        List<QueuePlacementPrepareRequest.Response> responses = responses(2);
        QueuePlacementPrepareRequest.Response firstResponse = responseFor(responses, MESSAGE_ID);
        QueuePlacementPrepareRequest.Response duplicateResponse = responseFor(responses, SECOND_MESSAGE_ID);
        assertCorrelated(firstResponse, first);
        assertCorrelated(duplicateResponse, duplicate);
        assertThat(responses).allSatisfy(response -> {
            assertThat(response.isAccepted()).isFalse();
            assertThat(response.getReason()).isEqualTo("game provider offline");
        });
    }

    @Test
    void completedDuplicateReplaysDecisionWithoutPreparingTwice() {
        when(preparer.prepare(any())).thenReturn(CompletableFuture.completedFuture(
                QueuePlacementPreparer.Decision.reject("game is full")));
        QueuePlacementPrepareRequest first = request();
        QueuePlacementPrepareRequest duplicate = request();
        duplicate.setMessageId(SECOND_MESSAGE_ID);

        receiveAndRun(first);
        this.handler.onReceive(duplicate);

        verify(scheduler).runTask(eq(plugin), any(Runnable.class));
        verify(preparer).prepare(any());
        List<QueuePlacementPrepareRequest.Response> responses = responses(2);
        assertCorrelated(responseFor(responses, MESSAGE_ID), first);
        assertCorrelated(responseFor(responses, SECOND_MESSAGE_ID), duplicate);
        assertThat(responses).allSatisfy(response -> {
            assertThat(response.isAccepted()).isFalse();
            assertThat(response.getReason()).isEqualTo("game is full");
        });
    }

    @Test
    void conflictingPayloadIsRejectedWithoutASecondPreparation() {
        CompletableFuture<QueuePlacementPreparer.Decision> decision = new CompletableFuture<>();
        when(preparer.prepare(any())).thenReturn(decision);
        QueuePlacementPrepareRequest first = request();
        QueuePlacementPrepareRequest conflict = request();
        conflict.setMessageId(SECOND_MESSAGE_ID);
        conflict.setRequests(Map.of("ticket-2", Set.of(MEMBER_ID)));

        this.handler.onReceive(first);
        scheduledTask().run();
        this.handler.onReceive(conflict);

        verify(preparer).prepare(any());
        verify(messaging).publish(eq("reply:coordinator"), any(QueuePlacementPrepareRequest.Response.class));

        decision.complete(QueuePlacementPreparer.Decision.accept());
        List<QueuePlacementPrepareRequest.Response> responses = responses(2);
        QueuePlacementPrepareRequest.Response conflictResponse = responseFor(responses, SECOND_MESSAGE_ID);
        assertCorrelated(conflictResponse, conflict);
        assertThat(conflictResponse.isAccepted()).isFalse();
        assertThat(conflictResponse.getReason()).isEqualTo("Conflicting Queue assignment revision");
        assertThat(responseFor(responses, MESSAGE_ID).isAccepted()).isTrue();
    }

    @Test
    void missingReplyTopicIsLoggedWithoutPublishing() {
        QueuePlacementPrepareRequest request = request();
        request.setQueueId(" ");
        request.setReplyTopic(null);

        this.handler.onReceive(request);

        verify(logger).warning("Queue preparation request has no reply topic");
        verify(messaging, never()).publish(any(), any());
    }

    @Test
    void asynchronousPublishFailureIsLogged() {
        IllegalStateException failure = new IllegalStateException("broker offline");
        when(messaging.publish(eq("reply:coordinator"), any(QueuePlacementPrepareRequest.Response.class)))
                .thenReturn(failedFuture(failure));
        QueuePlacementPrepareRequest request = request();
        request.setQueueId(" ");

        this.handler.onReceive(request);

        verify(logger).log(Level.WARNING, "Failed to reply to Queue preparation request", failure);
    }

    @Test
    void synchronousPublishFailureIsLogged() {
        IllegalStateException failure = new IllegalStateException("broker unavailable");
        when(messaging.publish(eq("reply:coordinator"), any(QueuePlacementPrepareRequest.Response.class)))
                .thenThrow(failure);
        QueuePlacementPrepareRequest request = request();
        request.setQueueId(" ");

        this.handler.onReceive(request);

        verify(logger).log(Level.WARNING, "Failed to reply to Queue preparation request", failure);
    }

    private QueuePlacementPrepareRequestHandler handler(Supplier<QueuePlacementPreparer> provider) {
        return new QueuePlacementPrepareRequestHandler(plugin, echo, stopping::get, provider,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private QueuePlacementPrepareRequest request() {
        QueuePlacementPrepareRequest request = new QueuePlacementPrepareRequest(PLACEMENT_ID, 2,
                "survival:classic", "game-1", NOW.plusSeconds(10).toEpochMilli(),
                Map.of("ticket-1", Set.of(MEMBER_ID)));
        request.setMessageId(MESSAGE_ID);
        request.setReplyTopic("reply:coordinator");
        return request;
    }

    private ServerLoadSnapshot load(boolean acceptingAssignments, Instant validUntil) {
        return new ServerLoadSnapshot(new ServerLoad(0, acceptingAssignments), NOW, validUntil);
    }

    private void receiveAndRun(QueuePlacementPrepareRequest request) {
        this.handler.onReceive(request);
        scheduledTask().run();
    }

    private Runnable scheduledTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTask(eq(plugin), task.capture());
        return task.getValue();
    }

    private QueuePlacementPrepareRequest.Response response() {
        return responses(1).getFirst();
    }

    private List<QueuePlacementPrepareRequest.Response> responses(int count) {
        ArgumentCaptor<QueuePlacementPrepareRequest.Response> response =
                ArgumentCaptor.forClass(QueuePlacementPrepareRequest.Response.class);
        verify(messaging, times(count)).publish(eq("reply:coordinator"), response.capture());
        return response.getAllValues();
    }

    private QueuePlacementPrepareRequest.Response responseFor(
            List<QueuePlacementPrepareRequest.Response> responses, UUID messageId) {
        return responses.stream()
                .filter(response -> messageId.equals(response.getMessageId()))
                .findFirst()
                .orElseThrow();
    }

    private void assertRejected(QueuePlacementPrepareRequest request, String reason) {
        QueuePlacementPrepareRequest.Response response = response();
        assertCorrelated(response, request);
        assertThat(response.isAccepted()).isFalse();
        assertThat(response.getReason()).isEqualTo(reason);
    }

    private void assertCorrelated(QueuePlacementPrepareRequest.Response response,
                                  QueuePlacementPrepareRequest request) {
        assertThat(response.getMessageId()).isEqualTo(request.getMessageId());
        assertThat(response.getPlacementId()).isEqualTo(request.getPlacementId());
        assertThat(response.getRunVersion()).isEqualTo(request.getRunVersion());
        assertThat(response.getReplyTopic()).isEqualTo("server:game-1");
    }

    private static <T> EchoFuture<T> failedFuture(Throwable failure) {
        EchoFuture<T> future = new EchoFuture<>();
        future.completeExceptionally(failure);
        return future;
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advanceSeconds(long seconds) {
            this.now = this.now.plusSeconds(seconds);
        }

        private void advanceMillis(long millis) {
            this.now = this.now.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }
}
