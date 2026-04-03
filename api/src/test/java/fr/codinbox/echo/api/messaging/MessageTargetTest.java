package fr.codinbox.echo.api.messaging;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class MessageTargetTest {

    @Test
    void everyone_returnsBroadcastTopic() {
        MessageTarget target = MessageTarget.everyone();

        assertThat(target.getTargets()).containsExactly(MessageTarget.BROADCAST_TOPIC);
    }

    @Test
    void constructor_storesTargets() {
        Set<String> targets = Set.of("topic1", "topic2");
        MessageTarget target = new MessageTarget(targets);

        assertThat(target.getTargets()).containsExactlyInAnyOrderElementsOf(targets);
    }

    @Test
    void getTargets_returnsUnmodifiableCopy() {
        MessageTarget target = new MessageTarget(Set.of("topic1"));

        Set<String> returned = target.getTargets();

        assertThatThrownBy(() -> returned.add("topic2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
