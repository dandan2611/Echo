package fr.codinbox.echo.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import fr.codinbox.echo.api.server.placement.ServerPlacement;
import fr.codinbox.echo.queue.QueueAdministration;
import fr.codinbox.echo.queue.QueueDefinition;
import fr.codinbox.echo.queue.QueueId;
import fr.codinbox.echo.queue.QueueRequest;
import fr.codinbox.echo.queue.QueueRequestStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class QueueSnapshotTest {

    private static final @NotNull QueueId QUEUE = new QueueId("survival-games");
    private static final @NotNull Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Test
    void countsWaitingPlayersAndOldestQueuedRequest() {
        final QueueAdministration.QueueOverview overview = new QueueAdministration.QueueOverview(
                definition(),
                false,
                null,
                Map.of(QueueRequestStatus.State.QUEUED, 2L, QueueRequestStatus.State.COMPLETED, 5L),
                null,
                null,
                null);

        final QueueSnapshot.QueueSample sample = QueueSnapshot.sample(
                overview,
                List.of(
                        ticket(QueueRequestStatus.State.QUEUED, 2, NOW.minusSeconds(30)),
                        ticket(QueueRequestStatus.State.QUEUED, 1, NOW.minusSeconds(90)),
                        ticket(QueueRequestStatus.State.TRANSFERRING, 4, NOW.minusSeconds(300))),
                NOW);

        assertThat(sample.queue()).isEqualTo("survival-games");
        assertThat(sample.waitingPlayers()).isEqualTo(3);
        assertThat(sample.oldestWaiting()).isEqualTo(Duration.ofSeconds(90));
        assertThat(sample.requests())
                .containsEntry(QueueRequestStatus.State.QUEUED, 2L)
                .containsEntry(QueueRequestStatus.State.COMPLETED, 5L)
                .containsEntry(QueueRequestStatus.State.FAILED, 0L)
                .hasSize(QueueRequestStatus.State.values().length);
    }

    @Test
    void nobodyWaiting() {
        final QueueAdministration.QueueOverview overview =
                new QueueAdministration.QueueOverview(definition(), true, "maintenance", Map.of(), null, null, null);

        final QueueSnapshot.QueueSample sample = QueueSnapshot.sample(overview, List.of(), NOW);

        assertThat(sample.paused()).isTrue();
        assertThat(sample.waitingPlayers()).isZero();
        assertThat(sample.oldestWaiting()).isNull();
    }

    private static @NotNull QueueDefinition definition() {
        return new QueueDefinition(QUEUE, "survival-games", Map.of(), ServerPlacement.Policy.SPREAD_LEAST_LOADED);
    }

    private static @NotNull QueueAdministration.QueueTicket ticket(
            final @NotNull QueueRequestStatus.State state, final int members, final @NotNull Instant createdAt) {
        final Set<UUID> players = new java.util.HashSet<>();
        for (int i = 0; i < members; i++) players.add(UUID.randomUUID());
        final QueueRequest request = new QueueRequest(UUID.randomUUID().toString(), QUEUE, players);
        return new QueueAdministration.QueueTicket(
                new QueueRequestStatus(request, 0, state, null, null, Map.of(), null), createdAt, createdAt);
    }
}
