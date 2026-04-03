package fr.codinbox.echo.core.integration;

import com.fasterxml.jackson.annotation.JsonProperty;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.core.EchoClientImpl;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class MessagingIntegrationTest extends RedisIntegrationTestBase {

    private EchoClientImpl client;
    private MessagingProvider messagingProvider;

    @NoArgsConstructor
    @Getter
    @Setter
    public static class TestMessage extends EchoMessage {
        private String payload;

        public TestMessage(String payload) {
            this.payload = payload;
        }
    }

    @BeforeEach
    void setUp() {
        client = createClient(EchoResourceType.SERVER, "test-server");
        messagingProvider = client.getMessagingProvider();
    }

    @Test
    void publishAndSubscribe_receivesMessage() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<EchoMessage> received = new AtomicReference<>();

        messagingProvider.subscribe("test:topic", message -> {
            received.set(message);
            latch.countDown();
        });

        // Allow subscription to register before publishing
        Thread.sleep(200);

        TestMessage msg = new TestMessage("hello");
        messagingProvider.publish("test:topic", msg).join();

        boolean completed = latch.await(5, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(received.get()).isNotNull();
        assertThat(received.get().getMessageId()).isEqualTo(msg.getMessageId());
    }

    @Test
    void handleReply_withWaitForReply_dispatches() {
        TestMessage original = new TestMessage("request");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<EchoMessage> receivedReply = new AtomicReference<>();

        messagingProvider.waitForReply(original, reply -> {
            receivedReply.set(reply);
            latch.countDown();
            return true;
        });

        TestMessage replyMsg = new TestMessage("response");
        replyMsg.setMessageId(original.getMessageId());

        boolean handled = messagingProvider.handleReply(replyMsg);

        assertThat(handled).isTrue();
        assertThat(receivedReply.get()).isNotNull();
        assertThat(receivedReply.get().getMessageId()).isEqualTo(original.getMessageId());
    }
}
