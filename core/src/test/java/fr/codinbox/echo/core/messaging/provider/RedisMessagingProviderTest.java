package fr.codinbox.echo.core.messaging.provider;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessageHandler;
import fr.codinbox.echo.api.messaging.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RFuture;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RedisMessagingProviderTest {

    private RedisMessagingProvider provider;
    private RTopic topic;
    private RFuture<Long> publishFuture;
    private RFuture<Void> removeFuture;
    private MessageListener<EchoMessage> listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisConnection mockConnection = mock(RedisConnection.class);
        RedissonClient client = mock(RedissonClient.class);
        topic = mock(RTopic.class);
        publishFuture = mock(RFuture.class);
        removeFuture = mock(RFuture.class);
        lenient().when(mockConnection.getClient()).thenReturn(client);
        lenient().when(client.getTopic("target")).thenReturn(topic);
        lenient().when(topic.publishAsync(any(EchoMessage.class))).thenReturn(publishFuture);
        lenient().when(publishFuture.toCompletableFuture()).thenReturn(CompletableFuture.completedFuture(1L));
        lenient().when(topic.addListener(eq(EchoMessage.class), any(MessageListener.class)))
                .thenAnswer(invocation -> {
                    listener = invocation.getArgument(1);
                    return 17;
                });
        lenient().when(topic.removeListenerAsync(anyInt())).thenReturn(removeFuture);
        lenient().when(removeFuture.toCompletableFuture()).thenReturn(CompletableFuture.completedFuture(null));
        provider = new RedisMessagingProvider(mockConnection);
    }

    @Test
    void lifecycleAndSubscriptions_removeOnlyTheOwnedListener() {
        assertThat(provider.init()).isCompleted();
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        MessageHandler<EchoMessage> firstHandler = ignored -> firstCalls.incrementAndGet();
        Subscription first = provider.subscribe("target", firstHandler);
        Subscription second = provider.subscribe("target", ignored -> secondCalls.incrementAndGet());

        assertThat(first.getTopic()).isEqualTo("target");
        assertThat(first.getHandler()).isSameAs(firstHandler);
        verify(topic, times(1)).addListener(eq(EchoMessage.class), any(MessageListener.class));

        listener.onMessage("target", mock(EchoMessage.class));
        assertThat(firstCalls).hasValue(1);
        assertThat(secondCalls).hasValue(1);

        first.cancel().join();
        listener.onMessage("target", mock(EchoMessage.class));
        assertThat(firstCalls).hasValue(1);
        assertThat(secondCalls).hasValue(2);

        second.cancel().join();
        verify(topic).removeListenerAsync(17);
        listener.onMessage("target", mock(EchoMessage.class));
        assertThat(secondCalls).hasValue(2);
        second.cancel().join();
    }

    @Test
    void shutdown_waitsForListenerRemovalAndClearsHandlersAndWaiters() {
        CompletableFuture<Void> removal = new CompletableFuture<>();
        when(removeFuture.toCompletableFuture()).thenReturn(removal);
        AtomicInteger calls = new AtomicInteger();
        provider.subscribe("target", ignored -> calls.incrementAndGet());
        EchoMessage request = mock(EchoMessage.class);
        UUID messageId = UUID.nameUUIDFromBytes("shutdown".getBytes());
        when(request.getMessageId()).thenReturn(messageId);
        provider.waitForReply(request, ignored -> false);

        CompletableFuture<Void> shutdown = provider.shutdown();

        assertThat(shutdown).isNotDone();
        listener.onMessage("target", request);
        assertThat(calls).hasValue(0);
        assertThat(provider.handleReply(request)).isTrue();
        removal.complete(null);
        assertThat(shutdown).isCompleted();
        verify(topic).removeListenerAsync(17);
    }

    @Test
    void waitForReply_shouldStoreConsumer() {
        EchoMessage message = mock(EchoMessage.class);
        UUID messageId = UUID.randomUUID();
        when(message.getMessageId()).thenReturn(messageId);

        provider.waitForReply(message, msg -> true);

        // Verify the consumer is stored by calling handleReply which should find it
        EchoMessage reply = mock(EchoMessage.class);
        when(reply.getMessageId()).thenReturn(messageId);
        boolean result = provider.handleReply(reply);
        assertThat(result).isTrue();
    }

    @Test
    void handleReply_withMatchingConsumer_shouldInvokeAndReturnItsResult() {
        UUID messageId = UUID.randomUUID();

        EchoMessage original = mock(EchoMessage.class);
        when(original.getMessageId()).thenReturn(messageId);
        provider.waitForReply(original, msg -> false);

        EchoMessage reply = mock(EchoMessage.class);
        when(reply.getMessageId()).thenReturn(messageId);
        boolean result = provider.handleReply(reply);

        assertThat(result).isFalse();
    }

    @Test
    void handleReply_withoutConsumer_shouldReturnTrue() {
        EchoMessage reply = mock(EchoMessage.class);
        when(reply.getMessageId()).thenReturn(UUID.randomUUID());

        boolean result = provider.handleReply(reply);

        assertThat(result).isTrue();
    }

    @Test
    void handleReply_withConsumerThatThrows_shouldReturnTrue() {
        UUID messageId = UUID.randomUUID();

        EchoMessage original = mock(EchoMessage.class);
        when(original.getMessageId()).thenReturn(messageId);
        provider.waitForReply(original, msg -> {
            throw new RuntimeException("boom");
        });

        EchoMessage reply = mock(EchoMessage.class);
        when(reply.getMessageId()).thenReturn(messageId);
        boolean result = provider.handleReply(reply);

        assertThat(result).isTrue();
    }

    @Test
    void request_registersBeforePublishingAndAcceptsOnce() {
        TestMessage request = request();
        TestResponse response = responseTo(request);
        when(topic.publishAsync(request)).thenAnswer(ignored -> {
            provider.handleReply(response);
            return publishFuture;
        });

        EchoFuture<TestResponse> result = provider.request(
                "target", request, TestResponse.class, Duration.ofSeconds(1));

        assertThat(result.join()).isSameAs(response);
        assertCanReuseMessageId(request.getMessageId());
    }

    @Test
    void request_ignoresWrongTypeAndRejectsDuplicateWaiter() {
        TestMessage first = request();
        EchoFuture<TestResponse> pending = provider.request(
                "target", first, TestResponse.class, Duration.ofSeconds(30));
        TestMessage duplicate = request();
        duplicate.setMessageId(first.getMessageId());

        assertThatThrownBy(() -> provider.request(
                "target", duplicate, TestResponse.class, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(first.getMessageId().toString());

        TestMessage wrongType = request();
        wrongType.setMessageId(first.getMessageId());
        assertThat(provider.handleReply(wrongType)).isFalse();
        assertThat(pending).isNotDone();

        TestResponse response = responseTo(first);
        assertThat(provider.handleReply(response)).isTrue();
        assertThat(pending.join()).isSameAs(response);
    }

    @Test
    void request_rejectsInvalidTimeoutAndMissingReplyTopic() {
        TestMessage request = request();
        assertThatThrownBy(() -> provider.request(
                "target", request, TestResponse.class, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.request(
                "target", request, TestResponse.class, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.request(
                "target", request, TestResponse.class, Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);

        request.setReplyTopic(null);
        assertThatThrownBy(() -> provider.request(
                "target", request, TestResponse.class, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Request has no reply topic");
        verify(topic, never()).publishAsync(request);
    }

    @Test
    void request_publishFailureFailsAndRemovesWaiter() {
        TestMessage first = request();
        when(publishFuture.toCompletableFuture())
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("publish failed")));

        assertThatThrownBy(() -> provider.request(
                "target", first, TestResponse.class, Duration.ofSeconds(1)).join())
                .hasRootCauseMessage("publish failed");

        TestMessage replay = request();
        replay.setMessageId(first.getMessageId());
        when(publishFuture.toCompletableFuture()).thenReturn(CompletableFuture.completedFuture(1L));
        TestResponse response = responseTo(replay);
        when(topic.publishAsync(replay)).thenAnswer(ignored -> {
            provider.handleReply(response);
            return publishFuture;
        });
        assertThat(provider.request("target", replay, TestResponse.class, Duration.ofSeconds(1)).join())
                .isSameAs(response);
    }

    @Test
    void request_timeoutAndCancellationRemoveWaiter() {
        TestMessage timedOut = request();
        EchoFuture<TestResponse> timedOutResult = provider.request(
                "target", timedOut, TestResponse.class, Duration.ofMillis(1));
        assertThatThrownBy(() -> timedOutResult.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
        assertCanReuseMessageId(timedOut.getMessageId());

        TestMessage cancelled = request();
        EchoFuture<TestResponse> pending = provider.request(
                "target", cancelled, TestResponse.class, Duration.ofSeconds(30));
        assertThat(pending.cancel(false)).isTrue();
        assertCanReuseMessageId(cancelled.getMessageId());
    }

    private void assertCanReuseMessageId(UUID messageId) {
        TestMessage replay = request();
        replay.setMessageId(messageId);
        TestResponse response = responseTo(replay);
        when(topic.publishAsync(replay)).thenAnswer(ignored -> {
            provider.handleReply(response);
            return publishFuture;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (true) {
            try {
                assertThat(provider.request("target", replay, TestResponse.class, Duration.ofSeconds(1)).join())
                        .isSameAs(response);
                return;
            } catch (IllegalStateException waiterStillCleaningUp) {
                if (System.nanoTime() >= deadline)
                    throw waiterStillCleaningUp;
                Thread.onSpinWait();
            }
        }
    }

    private static TestMessage request() {
        TestMessage request = new TestMessage();
        request.setReplyTopic("reply");
        return request;
    }

    private static TestResponse responseTo(TestMessage request) {
        TestResponse response = new TestResponse();
        response.setMessageId(request.getMessageId());
        return response;
    }

    private static final class TestMessage extends EchoMessage {
    }

    private static final class TestResponse extends EchoMessage {
    }
}
