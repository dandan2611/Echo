package fr.codinbox.echo.api.messaging;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class MessageTargetTest {

    @Test
    void getTargets_returnsUnmodifiableCopy() {
        MessageTarget target = new MessageTarget(Set.of("topic1"));

        Set<String> returned = target.getTargets();

        assertThatThrownBy(() -> returned.add("topic2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void builder_preservesTopicSemantics() {
        MessageTarget target = MessageTarget.builder()
                .withServers("server-1", "server-2")
                .withProxy("proxy-1")
                .withAllServers()
                .withAllProxies()
                .withBroadcast()
                .build();

        assertThat(target.getTargets()).containsExactlyInAnyOrder(
                "server:server-1", "server:server-2", "proxy:proxy-1",
                MessageTarget.SERVERS_TOPIC, MessageTarget.PROXIES_TOPIC,
                MessageTarget.BROADCAST_TOPIC);
    }
}
