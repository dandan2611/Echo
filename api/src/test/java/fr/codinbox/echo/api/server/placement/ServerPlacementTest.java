package fr.codinbox.echo.api.server.placement;

import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.property.PropertyKey;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ServerPlacementTest {

    @Test
    void request_snapshotsIndivisibleMembersCandidatesAndFilters() {
        UUID player = UUID.randomUUID();
        Set<UUID> members = new HashSet<>(Set.of(player));
        Set<String> candidates = new HashSet<>(Set.of("lobby-1"));
        Map<PropertyKey<?>, Object> filters = new HashMap<>(Map.of(new PropertyKey<>("type"), "lobby"));

        ServerPlacement.Request request = new ServerPlacement.Request("request-1", members, candidates,
                filters, ServerPlacement.Policy.FILL_MOST_LOADED, Duration.ofSeconds(30));
        members.clear();
        candidates.clear();
        filters.clear();

        assertThat(request.members()).containsExactly(player);
        assertThat(request.candidateServerIds()).containsExactly("lobby-1");
        assertThat(request.exactProperties()).containsEntry(new PropertyKey<>("type"), "lobby");
        assertThatThrownBy(() -> request.members().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.candidateServerIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.exactProperties().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void request_rejectsEmptyGroupAndNonPositiveLease() {
        assertThatThrownBy(() -> request(Set.of(), Map.of(), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("members");
        assertThatThrownBy(() -> request(Set.of(UUID.randomUUID()), Map.of(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease");
        assertThatThrownBy(() -> request(Set.of(UUID.randomUUID()), Map.of(), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease");
        assertThatThrownBy(() -> request(Set.of(UUID.randomUUID()), Map.of(), Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease");
    }

    @Test
    void request_rejectsBlankIdentifiersAndProperties() {
        assertThatThrownBy(() -> new ServerPlacement.Request(" ", Set.of(UUID.randomUUID()), Set.of(), Map.of(),
                ServerPlacement.Policy.FILL_MOST_LOADED, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestId");
        assertThatThrownBy(() -> new ServerPlacement.Request("request-1", Set.of(UUID.randomUUID()), Set.of(" "),
                Map.of(), ServerPlacement.Policy.FILL_MOST_LOADED, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidate server IDs");
        assertThatThrownBy(() -> request(Set.of(UUID.randomUUID()),
                Map.of(new PropertyKey<>(" "), "value"), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("property key");

        Map<PropertyKey<?>, Object> nullKey = new HashMap<>();
        nullKey.put(null, "value");
        assertThatThrownBy(() -> request(Set.of(UUID.randomUUID()), nullKey, Duration.ofSeconds(30)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("property key");

        Map<PropertyKey<?>, Object> nullValue = new HashMap<>();
        nullValue.put(new PropertyKey<>("type"), null);
        assertThatThrownBy(() -> request(Set.of(UUID.randomUUID()), nullValue, Duration.ofSeconds(30)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("property value");
    }

    @Test
    void request_rejectsNullRequiredValues() {
        UUID member = UUID.randomUUID();

        assertThatThrownBy(() -> new ServerPlacement.Request(null, Set.of(member), Set.of(), Map.of(),
                ServerPlacement.Policy.FILL_MOST_LOADED, Duration.ofSeconds(30)))
                .isInstanceOf(NullPointerException.class).hasMessage("requestId");
        assertThatThrownBy(() -> new ServerPlacement.Request("request-1", null, Set.of(), Map.of(),
                ServerPlacement.Policy.FILL_MOST_LOADED, Duration.ofSeconds(30)))
                .isInstanceOf(NullPointerException.class).hasMessage("members");
        assertThatThrownBy(() -> new ServerPlacement.Request("request-1", Set.of(member), null, Map.of(),
                ServerPlacement.Policy.FILL_MOST_LOADED, Duration.ofSeconds(30)))
                .isInstanceOf(NullPointerException.class).hasMessage("candidateServerIds");
        assertThatThrownBy(() -> new ServerPlacement.Request("request-1", Set.of(member), Set.of(), null,
                ServerPlacement.Policy.FILL_MOST_LOADED, Duration.ofSeconds(30)))
                .isInstanceOf(NullPointerException.class).hasMessage("exactProperties");
        assertThatThrownBy(() -> new ServerPlacement.Request("request-1", Set.of(member), Set.of(), Map.of(),
                null, Duration.ofSeconds(30)))
                .isInstanceOf(NullPointerException.class).hasMessage("policy");
        assertThatThrownBy(() -> new ServerPlacement.Request("request-1", Set.of(member), Set.of(), Map.of(),
                ServerPlacement.Policy.FILL_MOST_LOADED, null))
                .isInstanceOf(NullPointerException.class).hasMessage("lease");
    }

    @Test
    void request_rejectsPlacementControlledFilters() {
        for (String key : Set.of("availability", "load", "placement_capacity")) {
            assertThatThrownBy(() -> request(Set.of(UUID.randomUUID()),
                    Map.of(new PropertyKey<>(key), "value"), Duration.ofSeconds(30)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(key);
        }
    }

    @Test
    void reservation_snapshotsMembersAndIsImmutable() {
        UUID player = UUID.randomUUID();
        Set<UUID> members = new HashSet<>(Set.of(player));

        ServerPlacement.Reservation reservation = new ServerPlacement.Reservation(
                "request-1", "token-1", "lobby-1", members, Instant.parse("2026-09-03T12:00:30Z"));
        members.clear();

        assertThat(reservation.members()).containsExactly(player);
        assertThatThrownBy(() -> reservation.members().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reservation_rejectsBlankIdentifiersAndEmptyMembers() {
        UUID member = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-09-03T12:00:30Z");

        assertThatThrownBy(() -> new ServerPlacement.Reservation(" ", "token-1", "lobby-1",
                Set.of(member), expiresAt))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestId");
        assertThatThrownBy(() -> new ServerPlacement.Reservation("request-1", " ", "lobby-1",
                Set.of(member), expiresAt))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("token");
        assertThatThrownBy(() -> new ServerPlacement.Reservation("request-1", "token-1", " ",
                Set.of(member), expiresAt))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("serverId");
        assertThatThrownBy(() -> new ServerPlacement.Reservation("request-1", "token-1", "lobby-1",
                Set.of(), expiresAt))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("members");
    }

    @Test
    void reservation_rejectsNullRequiredValues() {
        UUID member = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-09-03T12:00:30Z");

        assertThatThrownBy(() -> new ServerPlacement.Reservation(null, "token-1", "lobby-1",
                Set.of(member), expiresAt))
                .isInstanceOf(NullPointerException.class).hasMessage("requestId");
        assertThatThrownBy(() -> new ServerPlacement.Reservation("request-1", null, "lobby-1",
                Set.of(member), expiresAt))
                .isInstanceOf(NullPointerException.class).hasMessage("token");
        assertThatThrownBy(() -> new ServerPlacement.Reservation("request-1", "token-1", null,
                Set.of(member), expiresAt))
                .isInstanceOf(NullPointerException.class).hasMessage("serverId");
        assertThatThrownBy(() -> new ServerPlacement.Reservation("request-1", "token-1", "lobby-1",
                null, expiresAt))
                .isInstanceOf(NullPointerException.class).hasMessage("members");
        assertThatThrownBy(() -> new ServerPlacement.Reservation("request-1", "token-1", "lobby-1",
                Set.of(member), null))
                .isInstanceOf(NullPointerException.class).hasMessage("expiresAt");
    }

    @Test
    void monitoringDefaults_areCompatibleWithImplementationsThatOnlySupportLeases() {
        ServerPlacement placement = new ServerPlacement() {
            @Override
            public EchoFuture<Optional<Reservation>> reserve(Request request) {
                return EchoFuture.completed(Optional.empty());
            }

            @Override
            public EchoFuture<Optional<Reservation>> renew(Reservation reservation, Duration lease) {
                return EchoFuture.completed(Optional.empty());
            }

            @Override
            public EchoFuture<Boolean> release(Reservation reservation) {
                return EchoFuture.completed(false);
            }
        };

        assertThatThrownBy(() -> placement.listActiveReservations().join())
                .hasRootCauseInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placement.findActiveReservation("request-1").join())
                .hasRootCauseInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placement.inspectServer("lobby-1").join())
                .hasRootCauseInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placement.explain(request(Set.of(UUID.randomUUID()), Map.of(),
                Duration.ofSeconds(30))).join()).hasRootCauseInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void activeReservation_validatesAndSnapshotsMembers() {
        UUID member = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Set<UUID> members = new HashSet<>(Set.of(member));
        Instant expiresAt = Instant.parse("2026-09-03T12:00:30Z");
        ServerPlacement.ActiveReservation reservation = new ServerPlacement.ActiveReservation(
                "request-1", "lobby-1", members, expiresAt);
        members.clear();

        assertThat(reservation.members()).containsExactly(member);
        assertThatThrownBy(() -> reservation.members().clear()).isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> new ServerPlacement.ActiveReservation(
                null, "lobby-1", Set.of(member), expiresAt))
                .isInstanceOf(NullPointerException.class).hasMessage("requestId");
        assertThatThrownBy(() -> new ServerPlacement.ActiveReservation(
                " ", "lobby-1", Set.of(member), expiresAt))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestId");
        assertThatThrownBy(() -> new ServerPlacement.ActiveReservation(
                "request-1", null, Set.of(member), expiresAt))
                .isInstanceOf(NullPointerException.class).hasMessage("serverId");
        assertThatThrownBy(() -> new ServerPlacement.ActiveReservation(
                "request-1", " ", Set.of(member), expiresAt))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("serverId");
        assertThatThrownBy(() -> new ServerPlacement.ActiveReservation(
                "request-1", "lobby-1", null, expiresAt))
                .isInstanceOf(NullPointerException.class).hasMessage("members");
        assertThatThrownBy(() -> new ServerPlacement.ActiveReservation(
                "request-1", "lobby-1", Set.of(), expiresAt))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("members");
        assertThatThrownBy(() -> new ServerPlacement.ActiveReservation(
                "request-1", "lobby-1", Set.of(member), null))
                .isInstanceOf(NullPointerException.class).hasMessage("expiresAt");
    }

    @Test
    void serverStatus_rejectsInvalidFields() {
        assertThatThrownBy(() -> new ServerPlacement.ServerStatus(null, true,
                ServerPlacement.AvailabilityState.ACTIVE, OptionalInt.empty(), 0,
                OptionalInt.empty(), OptionalLong.empty(), true, true))
                .isInstanceOf(NullPointerException.class).hasMessage("serverId");
        assertThatThrownBy(() -> new ServerPlacement.ServerStatus(" ", true,
                ServerPlacement.AvailabilityState.ACTIVE, OptionalInt.empty(), 0,
                OptionalInt.empty(), OptionalLong.empty(), true, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("serverId");
        assertThatThrownBy(() -> new ServerPlacement.ServerStatus("lobby-1", true,
                null, OptionalInt.empty(), 0, OptionalInt.empty(), OptionalLong.empty(), true, true))
                .isInstanceOf(NullPointerException.class).hasMessage("availability");
        assertThatThrownBy(() -> new ServerPlacement.ServerStatus("lobby-1", true,
                ServerPlacement.AvailabilityState.ACTIVE, null, 0,
                OptionalInt.empty(), OptionalLong.empty(), true, true))
                .isInstanceOf(NullPointerException.class).hasMessage("participantLoad");
        assertThatThrownBy(() -> new ServerPlacement.ServerStatus("lobby-1", true,
                ServerPlacement.AvailabilityState.ACTIVE, OptionalInt.empty(), 0,
                null, OptionalLong.empty(), true, true))
                .isInstanceOf(NullPointerException.class).hasMessage("capacity");
        assertThatThrownBy(() -> new ServerPlacement.ServerStatus("lobby-1", true,
                ServerPlacement.AvailabilityState.ACTIVE, OptionalInt.empty(), 0,
                OptionalInt.empty(), null, true, true))
                .isInstanceOf(NullPointerException.class).hasMessage("freeSlots");
        assertThatThrownBy(() -> new ServerPlacement.ServerStatus("lobby-1", true,
                ServerPlacement.AvailabilityState.ACTIVE, OptionalInt.of(-1), 0,
                OptionalInt.of(10), OptionalLong.of(10), true, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("participantLoad");
        assertThatThrownBy(() -> new ServerPlacement.ServerStatus("lobby-1", true,
                ServerPlacement.AvailabilityState.ACTIVE, OptionalInt.empty(), -1,
                OptionalInt.of(10), OptionalLong.of(10), true, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reservedSlots");
        assertThatThrownBy(() -> new ServerPlacement.ServerStatus("lobby-1", true,
                ServerPlacement.AvailabilityState.ACTIVE, OptionalInt.empty(), 0,
                OptionalInt.of(0), OptionalLong.empty(), true, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capacity");
        assertThatThrownBy(() -> new ServerPlacement.ServerStatus("lobby-1", true,
                ServerPlacement.AvailabilityState.ACTIVE, OptionalInt.empty(), 0,
                OptionalInt.empty(), OptionalLong.of(-1), true, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("freeSlots");
    }

    @Test
    void candidateEvaluation_validatesEligibilityAndMeasurements() {
        ServerPlacement.ServerStatus status = status("lobby-1");
        assertThatThrownBy(() -> new ServerPlacement.CandidateEvaluation(null, Optional.empty(),
                ServerPlacement.RejectionReason.SERVER_NOT_REGISTERED, OptionalLong.empty()))
                .isInstanceOf(NullPointerException.class).hasMessage("serverId");
        assertThatThrownBy(() -> new ServerPlacement.CandidateEvaluation(" ", Optional.empty(),
                ServerPlacement.RejectionReason.SERVER_NOT_REGISTERED, OptionalLong.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("serverId");
        assertThatThrownBy(() -> new ServerPlacement.CandidateEvaluation("lobby-1", null,
                ServerPlacement.RejectionReason.NONE, OptionalLong.of(1)))
                .isInstanceOf(NullPointerException.class).hasMessage("status");
        assertThatThrownBy(() -> new ServerPlacement.CandidateEvaluation("lobby-1", Optional.of(status),
                null, OptionalLong.of(1)))
                .isInstanceOf(NullPointerException.class).hasMessage("rejectionReason");
        assertThatThrownBy(() -> new ServerPlacement.CandidateEvaluation("lobby-1", Optional.of(status),
                ServerPlacement.RejectionReason.NONE, null))
                .isInstanceOf(NullPointerException.class).hasMessage("effectiveLoad");
        assertThatThrownBy(() -> new ServerPlacement.CandidateEvaluation("lobby-1", Optional.of(status),
                ServerPlacement.RejectionReason.NONE, OptionalLong.of(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("effectiveLoad");
        assertThatThrownBy(() -> new ServerPlacement.CandidateEvaluation("lobby-1", Optional.of(status),
                ServerPlacement.RejectionReason.NONE, OptionalLong.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("eligible candidates require an effectiveLoad");
    }

    @Test
    void explanation_validatesSelectionAndSnapshotsCandidates() {
        ServerPlacement.CandidateEvaluation other = new ServerPlacement.CandidateEvaluation(
                "lobby-0", Optional.empty(), ServerPlacement.RejectionReason.SERVER_NOT_REGISTERED,
                OptionalLong.empty());
        ServerPlacement.CandidateEvaluation eligible = new ServerPlacement.CandidateEvaluation(
                "lobby-1", Optional.of(status("lobby-1")), ServerPlacement.RejectionReason.NONE,
                OptionalLong.of(5));
        var candidates = new java.util.ArrayList<>(List.of(other, eligible));
        ServerPlacement.Explanation explanation = new ServerPlacement.Explanation(
                Optional.of("lobby-1"), candidates);
        candidates.clear();

        assertThat(explanation.candidates()).containsExactly(other, eligible);
        assertThatThrownBy(() -> explanation.candidates().clear()).isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> new ServerPlacement.Explanation(null, List.of()))
                .isInstanceOf(NullPointerException.class).hasMessage("selectedServerId");
        assertThatThrownBy(() -> new ServerPlacement.Explanation(Optional.of(" "), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selectedServerId");
        assertThatThrownBy(() -> new ServerPlacement.Explanation(Optional.empty(), null))
                .isInstanceOf(NullPointerException.class).hasMessage("candidates");
        assertThatThrownBy(() -> new ServerPlacement.Explanation(Optional.of("missing"), List.of(eligible)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selectedServerId");

        ServerPlacement.CandidateEvaluation rejected = new ServerPlacement.CandidateEvaluation(
                "lobby-1", Optional.of(status("lobby-1")), ServerPlacement.RejectionReason.STALE_LOAD,
                OptionalLong.of(5));
        assertThatThrownBy(() -> new ServerPlacement.Explanation(Optional.of("lobby-1"), List.of(rejected)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selectedServerId");
    }

    private ServerPlacement.Request request(Set<UUID> members, Map<PropertyKey<?>, Object> filters, Duration lease) {
        return new ServerPlacement.Request("request-1", members, Set.of(), filters,
                ServerPlacement.Policy.FILL_MOST_LOADED, lease);
    }

    private ServerPlacement.ServerStatus status(String serverId) {
        return new ServerPlacement.ServerStatus(serverId, true,
                ServerPlacement.AvailabilityState.ACTIVE, OptionalInt.of(3), 2,
                OptionalInt.of(10), OptionalLong.of(5), true, true);
    }
}
