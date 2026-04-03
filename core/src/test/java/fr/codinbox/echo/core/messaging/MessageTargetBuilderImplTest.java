package fr.codinbox.echo.core.messaging;

import fr.codinbox.echo.api.messaging.MessageTarget;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class MessageTargetBuilderImplTest {

    @Test
    void withServer_shouldAddServerTopic() {
        MessageTarget target = new MessageTargetBuilderImpl()
                .withServer("myServer")
                .build();

        assertThat(target.getTargets()).containsExactly("server:myServer");
    }

    @Test
    void withServers_varargs_shouldAddAllServerTopics() {
        MessageTarget target = new MessageTargetBuilderImpl()
                .withServers("s1", "s2", "s3")
                .build();

        assertThat(target.getTargets()).containsExactlyInAnyOrder(
                "server:s1", "server:s2", "server:s3");
    }

    @Test
    void withProxy_shouldAddProxyTopic() {
        MessageTarget target = new MessageTargetBuilderImpl()
                .withProxy("myProxy")
                .build();

        assertThat(target.getTargets()).containsExactly("proxy:myProxy");
    }

    @Test
    void withProxies_varargs_shouldAddAllProxyTopics() {
        MessageTarget target = new MessageTargetBuilderImpl()
                .withProxies("p1", "p2")
                .build();

        assertThat(target.getTargets()).containsExactlyInAnyOrder(
                "proxy:p1", "proxy:p2");
    }

    @Test
    void withBroadcast_shouldAddBroadcastTopic() {
        MessageTarget target = new MessageTargetBuilderImpl()
                .withBroadcast()
                .build();

        assertThat(target.getTargets()).containsExactly(MessageTarget.BROADCAST_TOPIC);
    }

    @Test
    void build_shouldCreateMessageTargetWithAllTargets() {
        MessageTarget target = new MessageTargetBuilderImpl()
                .withServer("s1")
                .withProxy("p1")
                .withBroadcast()
                .build();

        assertThat(target.getTargets()).containsExactlyInAnyOrder(
                "server:s1", "proxy:p1", MessageTarget.BROADCAST_TOPIC);
    }

    @Test
    void chaining_multipleTypes_shouldAccumulateAllTargets() {
        MessageTarget target = new MessageTargetBuilderImpl()
                .withServer("s1")
                .withServer("s2")
                .withProxy("p1")
                .withProxies("p2", "p3")
                .withBroadcast()
                .build();

        assertThat(target.getTargets()).containsExactlyInAnyOrder(
                "server:s1", "server:s2",
                "proxy:p1", "proxy:p2", "proxy:p3",
                MessageTarget.BROADCAST_TOPIC);
    }
}
