package fr.codinbox.echo.api.server;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ServerLoadTest {

    @Test
    void load_rejectsNegativeParticipantCount() {
        assertThatThrownBy(() -> new ServerLoad(-1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshot_becomesStaleAtItsDeadline() {
        Instant sampledAt = Instant.parse("2026-09-03T12:00:00Z");
        ServerLoadSnapshot snapshot = new ServerLoadSnapshot(
                new ServerLoad(4, true), sampledAt, sampledAt.plusSeconds(30));

        assertThat(snapshot.isStale(sampledAt.plusSeconds(29))).isFalse();
        assertThat(snapshot.isStale(sampledAt.plusSeconds(30))).isTrue();
    }

    @Test
    void snapshot_rejectsNullValuesAndInvalidDeadline() {
        Instant sampledAt = Instant.parse("2026-09-03T12:00:00Z");
        ServerLoad load = new ServerLoad(0, false);

        assertThatThrownBy(() -> new ServerLoadSnapshot(null, sampledAt, sampledAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("load");
        assertThatThrownBy(() -> new ServerLoadSnapshot(load, null, sampledAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("sampledAt");
        assertThatThrownBy(() -> new ServerLoadSnapshot(load, sampledAt, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("validUntil");
        assertThatThrownBy(() -> new ServerLoadSnapshot(load, sampledAt, sampledAt.minusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validUntil");
        assertThatThrownBy(() -> new ServerLoadSnapshot(load, sampledAt, sampledAt).isStale(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("now");
    }

}
