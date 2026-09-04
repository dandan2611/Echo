package fr.codinbox.echo.paper.event;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@Tag("unit")
class ServerDrainEventTest {

    @Test
    void exposesDeadlineAndBukkitHandlers() {
        Instant deadline = Instant.parse("2026-09-03T12:30:00Z");

        ServerDrainEvent event = new ServerDrainEvent(deadline);

        assertThat(event.getDeadline()).isEqualTo(deadline);
        assertThat(event.getHandlers()).isSameAs(ServerDrainEvent.getHandlerList());
    }

    @Test
    void rejectsMissingDeadline() {
        assertThatNullPointerException().isThrownBy(() -> new ServerDrainEvent(null));
    }
}
