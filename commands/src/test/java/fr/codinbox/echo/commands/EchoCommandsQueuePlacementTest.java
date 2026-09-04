package fr.codinbox.echo.commands;

import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.server.ServerAvailability;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import fr.codinbox.echo.ondemand.OnDemandAdministration;
import fr.codinbox.echo.ondemand.ServerHandle;
import fr.codinbox.echo.ondemand.ServerRequest;
import fr.codinbox.echo.queue.QueueAdministration;
import fr.codinbox.echo.queue.QueueDefinition;
import fr.codinbox.echo.queue.QueueId;
import fr.codinbox.echo.queue.QueueRequest;
import fr.codinbox.echo.queue.QueueRequestStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static fr.codinbox.echo.commands.CommandTestSupport.echoFailed;
import static fr.codinbox.echo.commands.CommandTestSupport.failed;
import static fr.codinbox.echo.commands.CommandTestSupport.fixture;
import static fr.codinbox.echo.commands.CommandTestSupport.fixtureWithFailingOnDemandLoader;
import static fr.codinbox.echo.commands.CommandTestSupport.fixtureWithFailingQueueLoader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class EchoCommandsQueuePlacementTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    @Test
    void queueListsInfoAndTicketsCoverEmptyPresentMissingAndNullableFields() {
        CommandTestSupport.Fixture fixture = fixture();
        QueueAdministration.QueueOverview active = overview("a", false, null, null);
        QueueAdministration.QueueOverview paused = overview("z", true, "maintenance", "server-z");
        when(fixture.queueAdministration().listQueues()).thenReturn(
                CompletableFuture.completedFuture(List.of(paused, active)),
                CompletableFuture.completedFuture(List.of()),
                CompletableFuture.completedFuture(List.of(active, paused)),
                CompletableFuture.completedFuture(List.of(active, paused)),
                CompletableFuture.completedFuture(List.of()));

        fixture.commands().monitorQueues(fixture.context()).join();
        fixture.commands().queueList(fixture.context()).join();
        fixture.commands().queueInfo(fixture.context(), "a").join();
        fixture.commands().queueInfo(fixture.context(), "z").join();
        fixture.commands().queueInfo(fixture.context(), "missing").join();

        UUID member = UUID.randomUUID();
        QueueRequestStatus sparse = status("request-a", "a", member, QueueRequestStatus.State.QUEUED, null, null);
        QueueRequestStatus detailed = status("request-z", "z", member, QueueRequestStatus.State.FAILED,
                "server-z", "connection failed");
        when(fixture.queueAdministration().listTickets(new QueueId("a"))).thenReturn(
                CompletableFuture.completedFuture(List.of(
                        new QueueAdministration.QueueTicket(sparse, null, NOW),
                        new QueueAdministration.QueueTicket(detailed, NOW.minusSeconds(5), null))));
        when(fixture.queues().get(new QueueId("a"), "request-a"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(sparse)));
        when(fixture.queues().get(new QueueId("z"), "request-z"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(detailed)));
        when(fixture.queues().get(new QueueId("a"), "missing"))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        fixture.commands().queueTickets(fixture.context(), "a").join();
        fixture.commands().queueTicket(fixture.context(), "a", "request-a").join();
        fixture.commands().queueTicket(fixture.context(), "z", "request-z").join();
        fixture.commands().queueTicket(fixture.context(), "a", "missing").join();

        assertThat(fixture.output()).contains("Queues\n- a active", "z paused", "Queues\nNone",
                "id: a", "reason: none", "server: none", "reason: maintenance", "server: server-z",
                "ERROR: Queue not found: missing", "created=unknown", "updated=unknown",
                "request: request-a", "state: QUEUED", "failure: none", "failure: connection failed",
                "ERROR: Queue ticket not found: missing");
    }

    @Test
    void queueEnqueueCancelPauseResumeWakeAndPurgeCoverResultsAndValidation() {
        CommandTestSupport.Fixture fixture = fixture();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        QueueRequestStatus queued = status("request", "ranked", first,
                QueueRequestStatus.State.QUEUED, null, null);
        when(fixture.queues().enqueue(any())).thenReturn(CompletableFuture.completedFuture(queued));

        fixture.commands().queueEnqueue(fixture.context(), "ranked", "request",
                " " + first + "," + second + ", ").join();
        fixture.commands().queueEnqueue(fixture.context(), "ranked", "empty", ", ,").join();
        fixture.commands().queueEnqueue(fixture.context(), "ranked", "bad", "not-a-uuid").join();

        when(fixture.queues().cancel(new QueueId("ranked"), "request"))
                .thenReturn(CompletableFuture.completedFuture(true), CompletableFuture.completedFuture(false));
        fixture.commands().queueCancel(fixture.context(), "ranked", "request", false).join();
        fixture.commands().queueCancel(fixture.context(), "ranked", "request", true).join();
        fixture.commands().queueCancel(fixture.context(), "ranked", "request", true).join();

        when(fixture.queueAdministration().pause(new QueueId("ranked"), "planned maintenance"))
                .thenReturn(CompletableFuture.completedFuture(true), CompletableFuture.completedFuture(false));
        fixture.commands().queuePause(fixture.context(), "ranked", "planned maintenance", false).join();
        fixture.commands().queuePause(fixture.context(), "ranked", "planned maintenance", true).join();
        fixture.commands().queuePause(fixture.context(), "ranked", "planned maintenance", true).join();

        when(fixture.queueAdministration().resume(new QueueId("ranked")))
                .thenReturn(CompletableFuture.completedFuture(true), CompletableFuture.completedFuture(false));
        fixture.commands().queueResume(fixture.context(), "ranked", false).join();
        fixture.commands().queueResume(fixture.context(), "ranked", true).join();
        fixture.commands().queueResume(fixture.context(), "ranked", true).join();

        when(fixture.queueAdministration().wake(new QueueId("ranked")))
                .thenReturn(CompletableFuture.completedFuture(null), failed(new IllegalStateException("wake failed")));
        fixture.commands().queueWake(fixture.context(), "ranked").join();
        fixture.commands().queueWake(fixture.context(), "ranked").join();

        when(fixture.queueAdministration().purgeTerminal(new QueueId("ranked"), Duration.ofMinutes(5)))
                .thenReturn(CompletableFuture.completedFuture(3));
        fixture.commands().queuePurge(fixture.context(), "ranked", 5, false).join();
        fixture.commands().queuePurge(fixture.context(), "ranked", 5, true).join();
        fixture.commands().queuePurge(fixture.context(), "ranked", 0, true).join();

        ArgumentCaptor<QueueRequest> request = ArgumentCaptor.forClass(QueueRequest.class);
        verify(fixture.queues()).enqueue(request.capture());
        assertThat(request.getValue().members()).containsExactlyInAnyOrder(first, second);
        assertThat(fixture.output()).contains("OK: Queue request is QUEUED.",
                "ERROR: members must contain at least one UUID", "ERROR: Invalid UUID string",
                "WARN: Run: echo queue cancel ranked request --confirm", "OK: Queue request cancelled.",
                "WARN: Queue request was not cancellable.", "OK: Queue paused.",
                "WARN: Queue was already paused.", "OK: Queue resumed.", "WARN: Queue was not paused.",
                "OK: Queue worker wake requested.", "ERROR: Command failed. Reference:",
                "OK: Purged 3 terminal queue requests.", "ERROR: minutes must be positive");
        verify(fixture.logger(), atLeastOnce()).info(any(Supplier.class));
    }

    @Test
    void auditTargetsIdentifyQueueRequestAndPurgeAge() {
        CommandTestSupport.Fixture fixture = fixture();
        UUID member = UUID.randomUUID();
        QueueRequestStatus queued = status("request-1", "ranked", member,
                QueueRequestStatus.State.QUEUED, null, null);
        when(fixture.queues().enqueue(any())).thenReturn(CompletableFuture.completedFuture(queued));
        when(fixture.queues().cancel(new QueueId("ranked"), "request-1"))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(fixture.queueAdministration().purgeTerminal(new QueueId("ranked"), Duration.ofMinutes(5)))
                .thenReturn(CompletableFuture.completedFuture(2));

        fixture.commands().queueEnqueue(fixture.context(), "ranked", "request-1", member.toString()).join();
        fixture.commands().queueCancel(fixture.context(), "ranked", "request-1", true).join();
        fixture.commands().queuePurge(fixture.context(), "ranked", 5, true).join();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Supplier> entries = ArgumentCaptor.forClass(Supplier.class);
        verify(fixture.logger(), times(3)).info(entries.capture());
        assertThat(entries.getAllValues().stream().map(entry -> (String) entry.get()).toList()).contains(
                "echo_audit sender=audit_console_user action=queue.enqueue target=ranked/request-1 outcome=QUEUED",
                "echo_audit sender=audit_console_user action=queue.cancel target=ranked/request-1 outcome=true",
                "echo_audit sender=audit_console_user action=queue.purge target=ranked_olderThan=5m outcome=2");
    }

    @Test
    void retryAndRequeueCoverMissingWrongStateAndBothTerminalOutcomes() {
        CommandTestSupport.Fixture fixture = fixture();
        UUID member = UUID.randomUUID();
        QueueRequestStatus queued = status("queued", "ranked", member, QueueRequestStatus.State.QUEUED, null, null);
        QueueRequestStatus failed = status("failed", "ranked", member, QueueRequestStatus.State.FAILED, null, "bad");
        QueueRequestStatus cancelled = status("cancelled", "ranked", member,
                QueueRequestStatus.State.CANCELLED, null, null);
        when(fixture.queues().get(new QueueId("ranked"), "missing"))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(fixture.queues().get(new QueueId("ranked"), "queued"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(queued)));
        when(fixture.queues().get(new QueueId("ranked"), "failed"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(failed)),
                        CompletableFuture.completedFuture(Optional.of(failed)));
        when(fixture.queues().get(new QueueId("ranked"), "cancelled"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(cancelled)));
        when(fixture.queueAdministration().retry(new QueueId("ranked"), "failed"))
                .thenReturn(CompletableFuture.completedFuture(true), CompletableFuture.completedFuture(false));
        when(fixture.queueAdministration().retry(new QueueId("ranked"), "cancelled"))
                .thenReturn(CompletableFuture.completedFuture(true));

        fixture.commands().queueRetry(fixture.context(), "ranked", "missing", false).join();
        fixture.commands().queueRetry(fixture.context(), "ranked", "missing", true).join();
        fixture.commands().queueRetry(fixture.context(), "ranked", "queued", true).join();
        fixture.commands().queueRetry(fixture.context(), "ranked", "failed", true).join();
        fixture.commands().queueRetry(fixture.context(), "ranked", "failed", true).join();
        fixture.commands().queueRequeue(fixture.context(), "ranked", "cancelled", true).join();

        assertThat(fixture.output()).contains("WARN: Run: echo queue retry ranked missing --confirm",
                "ERROR: Queue ticket not found: missing",
                "ERROR: Retry is allowed only for FAILED/CANCELLED; current state is QUEUED.",
                "OK: Queue request retried.", "WARN: Queue request was not retried.");
        verify(fixture.queueAdministration(), times(2)).retry(new QueueId("ranked"), "failed");
        verify(fixture.queueAdministration()).retry(new QueueId("ranked"), "cancelled");
    }

    @Test
    void queueSuggestionsCoverSuccessAsyncFailureAndNotLoaded() {
        CommandTestSupport.Fixture fixture = fixture();
        when(fixture.queueAdministration().listQueues()).thenReturn(
                CompletableFuture.completedFuture(List.of(overview("z", false, null, null),
                        overview("a", false, null, null))),
                failed(new IllegalStateException("down")));
        assertThat(fixture.commands().queueSuggestions().join()).containsExactly("a", "z");
        assertThat(fixture.commands().queueSuggestions().join()).isEmpty();

        CommandTestSupport.Fixture unloaded = fixtureWithFailingQueueLoader();
        assertThat(unloaded.commands().queueSuggestions().join()).isEmpty();
        unloaded.commands().queueList(unloaded.context()).join();
        assertThat(unloaded.output()).isEqualTo("ERROR: Queue service is not loaded in this process.");
    }

    @Test
    void placementStatusExplainAndReservationViewsCoverOptionalBranches() {
        CommandTestSupport.Fixture fixture = fixture();
        ServerPlacement.ServerStatus complete = new ServerPlacement.ServerStatus("server-a", true,
                ServerPlacement.AvailabilityState.ACTIVE, OptionalInt.of(4), 2,
                OptionalInt.of(10), OptionalLong.of(4), true, true);
        ServerPlacement.ServerStatus sparse = new ServerPlacement.ServerStatus("server-b", false,
                ServerPlacement.AvailabilityState.DEFAULT_ACTIVE, OptionalInt.empty(), 0,
                OptionalInt.empty(), OptionalLong.empty(), false, false);
        when(fixture.placement().inspectServer("missing")).thenReturn(EchoFuture.completed(Optional.empty()));
        when(fixture.placement().inspectServer("server-a")).thenReturn(EchoFuture.completed(Optional.of(complete)));
        when(fixture.placement().inspectServer("server-b")).thenReturn(EchoFuture.completed(Optional.of(sparse)));
        fixture.commands().placementStatus(fixture.context(), "missing").join();
        fixture.commands().placementStatus(fixture.context(), "server-a").join();
        fixture.commands().placementStatus(fixture.context(), "server-b").join();

        UUID member = UUID.randomUUID();
        ServerPlacement.CandidateEvaluation rejected = new ServerPlacement.CandidateEvaluation("server-b",
                Optional.of(sparse), ServerPlacement.RejectionReason.INVALID_LOAD, OptionalLong.empty());
        ServerPlacement.CandidateEvaluation eligible = new ServerPlacement.CandidateEvaluation("server-a",
                Optional.of(complete), ServerPlacement.RejectionReason.NONE, OptionalLong.of(6));
        when(fixture.placement().explain(any())).thenReturn(
                EchoFuture.completed(new ServerPlacement.Explanation(Optional.empty(), List.of(rejected))),
                EchoFuture.completed(new ServerPlacement.Explanation(Optional.of("server-a"), List.of(eligible))));
        fixture.commands().placementExplain(fixture.context(), "bedwars", member.toString(),
                "SPREAD_LEAST_LOADED").join();
        fixture.commands().placementExplain(fixture.context(), "bedwars", member.toString(),
                "fill_most_loaded").join();

        ServerPlacement.ActiveReservation active = new ServerPlacement.ActiveReservation(
                "request", "server-a", Set.of(member), NOW);
        when(fixture.placement().listActiveReservations()).thenReturn(EchoFuture.completed(List.of()),
                EchoFuture.completed(List.of(active)));
        fixture.commands().monitorPlacement(fixture.context()).join();
        fixture.commands().placementReservations(fixture.context()).join();
        when(fixture.placement().findActiveReservation("request"))
                .thenReturn(EchoFuture.completed(Optional.of(active)));
        when(fixture.placement().findActiveReservation("missing"))
                .thenReturn(EchoFuture.completed(Optional.empty()));
        fixture.commands().placementReservation(fixture.context(), "request").join();
        fixture.commands().placementReservation(fixture.context(), "missing").join();

        ArgumentCaptor<ServerPlacement.Request> explanationRequest = ArgumentCaptor.forClass(ServerPlacement.Request.class);
        verify(fixture.placement(), times(2)).explain(explanationRequest.capture());
        assertThat(explanationRequest.getAllValues()).allSatisfy(request -> {
            assertThat(request.requestId()).startsWith("explain-");
            assertThat(request.members()).containsExactly(member);
            assertThat(request.lease()).isEqualTo(Duration.ofSeconds(15));
        });
        assertThat(fixture.output()).contains("ERROR: Server not found: missing", "participants: 4",
                "participants: unknown", "capacity: 10", "capacity: unknown", "free: 4", "free: unknown",
                "selected: none", "selected: server-a", "effectiveLoad=unknown", "effectiveLoad=6",
                "Placement reservations\nNone", "request -> server-a", "ERROR: Reservation not found: missing");
    }

    @Test
    void placementMutationsCoverUnavailableOwnershipRenewalAndReleaseResultsWithoutTokens() {
        CommandTestSupport.Fixture fixture = fixture();
        UUID member = UUID.randomUUID();
        ServerPlacement.Reservation first = new ServerPlacement.Reservation(
                "request", "secret-token-one", "server-a", Set.of(member), NOW);
        ServerPlacement.Reservation renewed = new ServerPlacement.Reservation(
                "request", "secret-token-two", "server-a", Set.of(member), NOW.plusSeconds(30));
        when(fixture.placement().reserve(any())).thenReturn(EchoFuture.completed(Optional.empty()),
                EchoFuture.completed(Optional.of(first)));

        fixture.commands().placementRenew(fixture.context(), "not-owned", 15).join();
        fixture.commands().placementRelease(fixture.context(), "not-owned", false).join();
        fixture.commands().placementRelease(fixture.context(), "not-owned", true).join();
        fixture.commands().placementReserve(fixture.context(), "unavailable", "bedwars", member.toString(),
                "SPREAD_LEAST_LOADED", 15).join();
        fixture.commands().placementReserve(fixture.context(), "request", "bedwars", member.toString(),
                "SPREAD_LEAST_LOADED", 15).join();

        when(fixture.placement().renew(first, Duration.ofSeconds(30)))
                .thenReturn(EchoFuture.completed(Optional.of(renewed)));
        when(fixture.placement().renew(renewed, Duration.ofSeconds(30)))
                .thenReturn(EchoFuture.completed(Optional.empty()));
        fixture.commands().placementRenew(fixture.context(), "request", 30).join();
        fixture.commands().placementRenew(fixture.context(), "request", 30).join();

        when(fixture.placement().release(renewed)).thenReturn(EchoFuture.completed(false), EchoFuture.completed(true));
        fixture.commands().placementRelease(fixture.context(), "request", true).join();
        fixture.commands().placementRelease(fixture.context(), "request", true).join();
        fixture.commands().placementRelease(fixture.context(), "request", true).join();

        ArgumentCaptor<ServerPlacement.Request> request = ArgumentCaptor.forClass(ServerPlacement.Request.class);
        verify(fixture.placement(), times(2)).reserve(request.capture());
        assertThat(request.getAllValues().get(1).exactProperties())
                .containsEntry(new PropertyKey<String>("server_type"), "bedwars");
        assertThat(fixture.output()).contains("ERROR: Reservation is not held by this process: not-owned",
                "WARN: No eligible server.", "Placement reservation", "WARN: Reservation is no longer active.",
                "WARN: Reservation was absent or superseded.", "OK: Reservation released.",
                "ERROR: Reservation is not held by this process: request")
                .doesNotContain("secret-token-one", "secret-token-two");
    }

    @Test
    void placementValidationAndSuggestionsCoverFailuresAndNotConfigured() {
        CommandTestSupport.Fixture fixture = fixture();
        UUID member = UUID.randomUUID();
        fixture.commands().placementExplain(fixture.context(), "bedwars", ", ", "SPREAD_LEAST_LOADED").join();
        fixture.commands().placementExplain(fixture.context(), "bedwars", "bad", "SPREAD_LEAST_LOADED").join();
        fixture.commands().placementExplain(fixture.context(), "bedwars", member.toString(), "mystery").join();
        fixture.commands().placementReserve(fixture.context(), "request", "bedwars", member.toString(),
                "SPREAD_LEAST_LOADED", 0).join();
        fixture.commands().placementReserve(fixture.context(), "", "bedwars", member.toString(),
                "SPREAD_LEAST_LOADED", 15).join();
        assertThat(fixture.output()).contains("ERROR: members must contain at least one UUID",
                "ERROR: Invalid UUID string", "ERROR: Unknown placement policy: mystery",
                "ERROR: leaseSeconds must be positive", "ERROR: requestId must not be blank");

        ServerPlacement.ActiveReservation value = new ServerPlacement.ActiveReservation(
                "z", "server", Set.of(member), NOW);
        when(fixture.placement().listActiveReservations()).thenReturn(EchoFuture.completed(List.of(value)),
                echoFailed(new IllegalStateException("down")));
        assertThat(fixture.commands().placementSuggestions().join()).containsExactly("z");
        assertThat(fixture.commands().placementSuggestions().join()).isEmpty();
        when(fixture.echo().getServerPlacement()).thenThrow(new IllegalStateException("not configured"));
        assertThat(fixture.commands().placementSuggestions().join()).isEmpty();
    }

    @Test
    void allocationsCoverListsInfoAcquireReconcileAndTerminateOutcomes() {
        CommandTestSupport.Fixture fixture = fixture();
        OnDemandAdministration.Allocation first = new OnDemandAdministration.Allocation("a", "server-a");
        OnDemandAdministration.Allocation second = new OnDemandAdministration.Allocation("z", "server-z");
        when(fixture.onDemandAdministration().listAllocations()).thenReturn(
                CompletableFuture.completedFuture(List.of()),
                CompletableFuture.completedFuture(List.of(second, first)));
        fixture.commands().allocationList(fixture.context()).join();
        fixture.commands().allocationList(fixture.context()).join();

        when(fixture.onDemandAdministration().getAllocation("a"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(first)));
        when(fixture.onDemandAdministration().getAllocation("missing"))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        fixture.commands().allocationInfo(fixture.context(), "a").join();
        fixture.commands().allocationInfo(fixture.context(), "missing").join();

        when(fixture.onDemand().acquire(new ServerRequest("request", "bedwars")))
                .thenReturn(CompletableFuture.completedFuture(new ServerHandle("allocated-server")));
        fixture.commands().allocationAcquire(fixture.context(), "request", "bedwars", false).join();
        fixture.commands().allocationAcquire(fixture.context(), "request", "bedwars", true).join();

        OnDemandAdministration.Reconciliation live = new OnDemandAdministration.Reconciliation(first, true,
                ServerAvailability.DRAINING);
        OnDemandAdministration.Reconciliation gone = new OnDemandAdministration.Reconciliation(second, false, null);
        when(fixture.onDemandAdministration().reconcile("missing"))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(fixture.onDemandAdministration().reconcile("a"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(live)));
        when(fixture.onDemandAdministration().reconcile("z"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(gone)));
        fixture.commands().allocationReconcile(fixture.context(), "missing").join();
        fixture.commands().allocationReconcile(fixture.context(), "a").join();
        fixture.commands().allocationReconcile(fixture.context(), "z").join();

        when(fixture.onDemandAdministration().terminate("a"))
                .thenReturn(CompletableFuture.completedFuture(true), CompletableFuture.completedFuture(false));
        fixture.commands().allocationTerminate(fixture.context(), "a", false).join();
        fixture.commands().allocationTerminate(fixture.context(), "a", true).join();
        fixture.commands().allocationTerminate(fixture.context(), "a", true).join();

        assertThat(fixture.output()).contains("Allocations\nNone", "a -> server-a", "z -> server-z",
                "request: a", "server: server-a", "ERROR: Allocation not found: missing",
                "WARN: Run: echo allocation acquire request bedwars --confirm",
                "OK: Allocated server allocated-server.", "availability: DRAINING", "availability: none",
                "OK: Allocation terminated.", "WARN: Allocation was not found.");
        verify(fixture.logger(), atLeastOnce()).info(any(Supplier.class));
    }

    @Test
    void allocationSuggestionsAndCommandsCoverAsyncFailureAndNotLoaded() {
        CommandTestSupport.Fixture fixture = fixture();
        when(fixture.onDemandAdministration().listAllocations()).thenReturn(
                CompletableFuture.completedFuture(List.of(
                        new OnDemandAdministration.Allocation("z", "server-z"),
                        new OnDemandAdministration.Allocation("a", "server-a"))),
                failed(new IllegalStateException("down")));
        assertThat(fixture.commands().allocationSuggestions().join()).containsExactly("a", "z");
        assertThat(fixture.commands().allocationSuggestions().join()).isEmpty();

        CommandTestSupport.Fixture unloaded = fixtureWithFailingOnDemandLoader();
        assertThat(unloaded.commands().allocationSuggestions().join()).isEmpty();
        unloaded.commands().allocationList(unloaded.context()).join();
        unloaded.commands().allocationAcquire(unloaded.context(), "request", "type", true).join();
        assertThat(unloaded.output()).contains("ERROR: On-demand servers are not loaded in this process.");
        verify(unloaded.logger()).info(any(Supplier.class));
    }

    @Test
    void queuePlacementAndAllocationSuggestionsAreCapped() {
        CommandTestSupport.Fixture fixture = fixture();
        when(fixture.queueAdministration().listQueues()).thenReturn(CompletableFuture.completedFuture(
                IntStream.range(0, 105).mapToObj(index -> overview(
                        "queue-%03d".formatted(104 - index), false, null, null)).toList()));
        when(fixture.placement().listActiveReservations()).thenReturn(EchoFuture.completed(
                IntStream.range(0, 105).mapToObj(index -> new ServerPlacement.ActiveReservation(
                        "placement-%03d".formatted(104 - index), "server", Set.of(new UUID(0, 1)), NOW)).toList()));
        when(fixture.onDemandAdministration().listAllocations()).thenReturn(CompletableFuture.completedFuture(
                IntStream.range(0, 105).mapToObj(index -> new OnDemandAdministration.Allocation(
                        "allocation-%03d".formatted(104 - index), "server")).toList()));

        assertThat(fixture.commands().queueSuggestions().join()).hasSize(100)
                .startsWith("queue-000").endsWith("queue-099");
        assertThat(fixture.commands().placementSuggestions().join()).hasSize(100)
                .startsWith("placement-000").endsWith("placement-099");
        assertThat(fixture.commands().allocationSuggestions().join()).hasSize(100)
                .startsWith("allocation-000").endsWith("allocation-099");
    }

    private static QueueAdministration.QueueOverview overview(String id, boolean paused,
                                                               String reason, String server) {
        QueueDefinition definition = new QueueDefinition(new QueueId(id), "bedwars", Map.of(),
                ServerPlacement.Policy.SPREAD_LEAST_LOADED);
        return new QueueAdministration.QueueOverview(definition, paused, reason,
                Map.of(QueueRequestStatus.State.QUEUED, 2L), null, server, null);
    }

    private static QueueRequestStatus status(String request, String queue, UUID member,
                                             QueueRequestStatus.State state, String server, String failure) {
        return new QueueRequestStatus(new QueueRequest(request, new QueueId(queue), Set.of(member)),
                2, state, null, server, Map.of(), failure);
    }
}
