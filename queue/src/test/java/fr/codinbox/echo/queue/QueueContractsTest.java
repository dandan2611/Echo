package fr.codinbox.echo.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import fr.codinbox.echo.queue.messaging.QueuePlacementPrepareRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

@Tag("unit")
class QueueContractsTest {

    @Test
    void defaults_preserveCoordinationTimeouts() {
        QueueOptions options = QueueOptions.defaults();

        assertThat(new Duration[] { options.transferTimeout(), options.transferReconciliationTimeout() })
                .containsExactly(Duration.ofSeconds(15), Duration.ofSeconds(1));
    }

    @Test
    void legacyQueueServicesRejectUnsupportedAdministration() {
        QueueService service = mock(QueueService.class, CALLS_REAL_METHODS);

        assertThatThrownBy(service::administration)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Queue administration is not supported");
    }

    @Test
    void requestsAndDefinitionsSnapshotCallerCollections() {
        Set<UUID> members = new java.util.HashSet<>(Set.of(UUID.randomUUID()));
        Map<PropertyKey<?>, Object> properties = new LinkedHashMap<>();
        properties.put(new PropertyKey<>("mode"), "classic");

        QueueRequest request = new QueueRequest("ticket-1", new QueueId("survival:classic"), members);
        QueueDefinition definition = new QueueDefinition(request.queueId(), "survival", properties,
                ServerPlacement.Policy.FILL_MOST_LOADED);
        members.clear();
        properties.clear();

        assertThat(request.members()).hasSize(1);
        assertThat(definition.serverProperties()).containsEntry(new PropertyKey<>("mode"), "classic");
        assertThatThrownBy(() -> request.members().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> definition.serverProperties().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void invalidContractsFailBeforeRedisIsTouched() {
        QueueId queueId = new QueueId("survival:classic");

        assertThatThrownBy(() -> new QueueId(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueueRequest(" ", queueId, Set.of(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueueRequest("ticket", queueId, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueueDefinition(queueId, " ", Map.of(),
                ServerPlacement.Policy.SPREAD_LEAST_LOADED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueueDefinition(queueId, "survival", Map.of(new PropertyKey<>(" "), true),
                ServerPlacement.Policy.SPREAD_LEAST_LOADED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueueOptions(Duration.ZERO, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueueOptions(Duration.ofNanos(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueueOptions(Duration.ofMillis(-1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueueOptions(Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservationTtl");
        assertThatThrownBy(() -> new QueueOptions(Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservationTtl");
        assertThatThrownBy(() -> new QueueOptions(Duration.ofSeconds(2), Duration.ofSeconds(3),
                Duration.ofSeconds(4), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pollInterval");
        QueueRequest request = new QueueRequest("ticket", queueId, Set.of(UUID.randomUUID()));
        assertThatThrownBy(() -> new QueueRequestStatus(request, -1, QueueRequestStatus.State.QUEUED,
                null, null, Map.of(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void placementAssignmentSnapshotsNestedMemberSets() {
        UUID member = UUID.randomUUID();
        Set<UUID> members = new HashSet<>(Set.of(member));
        Map<String, Set<UUID>> requests = new LinkedHashMap<>();
        requests.put("ticket-1", members);

        QueuePlacementAssignment assignment = new QueuePlacementAssignment(
                UUID.randomUUID(), 3, new QueueId("survival:classic"), "game-1",
                Instant.parse("2026-09-03T12:00:10Z"), requests);
        members.clear();
        requests.clear();

        assertThat(assignment.requests()).containsEntry("ticket-1", Set.of(member));
        assertThatThrownBy(() -> assignment.requests().get("ticket-1").clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> assignment.requests().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preparerDecisionRequiresAReasonWhenRejected() {
        assertThatThrownBy(() -> new QueuePlacementPreparer.Decision(true, "unexpected"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueuePlacementPreparer.Decision(false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueuePlacementPreparer.Decision(false, " "))
                .isInstanceOf(IllegalArgumentException.class);

        QueuePlacementPreparer preparer = ignored -> CompletableFuture.completedFuture(
                QueuePlacementPreparer.Decision.accept());
        assertThat(preparer.prepare(new QueuePlacementAssignment(UUID.randomUUID(), 0,
                new QueueId("survival:classic"), "game-1", Instant.parse("2026-09-03T12:00:10Z"),
                Map.of("ticket-1", Set.of(UUID.randomUUID())))))
                .succeedsWithin(Duration.ofSeconds(1));
    }

    @Test
    void invalidPlacementAssignmentsAreRejectedAtTheHandoffBoundary() {
        QueueId queueId = new QueueId("survival:classic");
        UUID placementId = UUID.randomUUID();
        Set<UUID> members = Set.of(UUID.randomUUID());

        Instant deadline = Instant.parse("2026-09-03T12:00:10Z");

        assertThatThrownBy(() -> new QueuePlacementAssignment(placementId, -1, queueId, "game-1", deadline,
                Map.of("ticket-1", members))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueuePlacementAssignment(placementId, 0, queueId, " ", deadline,
                Map.of("ticket-1", members))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueuePlacementAssignment(placementId, 0, queueId, "game-1", deadline,
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueuePlacementAssignment(placementId, 0, queueId, "game-1", null,
                Map.of("ticket-1", members))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueuePlacementAssignment(placementId, 0, queueId, "game-1", deadline,
                Map.of(" ", members))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueuePlacementAssignment(placementId, 0, queueId, "game-1", deadline,
                Map.of("ticket-1", Set.of()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preparationMessagesRoundTripThroughJackson() throws Exception {
        UUID placementId = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        Instant deadline = Instant.parse("2026-09-03T12:00:10Z");
        ObjectMapper mapper = new ObjectMapper();
        QueuePlacementPrepareRequest request = new QueuePlacementPrepareRequest(
                placementId, 4, "survival:classic", "game-1", deadline.toEpochMilli(),
                Map.of("ticket-1", Set.of(member)));
        QueuePlacementPrepareRequest.Response response = new QueuePlacementPrepareRequest.Response(
                placementId, 4, false, "full");

        QueuePlacementPrepareRequest restoredRequest = mapper.readValue(
                mapper.writeValueAsString(request), QueuePlacementPrepareRequest.class);
        QueuePlacementPrepareRequest.Response restoredResponse = mapper.readValue(
                mapper.writeValueAsString(response), QueuePlacementPrepareRequest.Response.class);

        assertThat(restoredRequest.getPlacementId()).isEqualTo(placementId);
        assertThat(restoredRequest.getRunVersion()).isEqualTo(4);
        assertThat(restoredRequest.getQueueId()).isEqualTo("survival:classic");
        assertThat(restoredRequest.getServerId()).isEqualTo("game-1");
        assertThat(restoredRequest.getPreparationDeadlineEpochMillis()).isEqualTo(deadline.toEpochMilli());
        assertThat(restoredRequest.getRequests()).containsEntry("ticket-1", Set.of(member));
        assertThat(restoredResponse.getPlacementId()).isEqualTo(placementId);
        assertThat(restoredResponse.getRunVersion()).isEqualTo(4);
        assertThat(restoredResponse.isAccepted()).isFalse();
        assertThat(restoredResponse.getReason()).isEqualTo("full");
    }
}
