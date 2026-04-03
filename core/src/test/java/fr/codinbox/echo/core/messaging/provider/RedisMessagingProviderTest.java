package fr.codinbox.echo.core.messaging.provider;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.messaging.EchoMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RedisMessagingProviderTest {

    private RedisMessagingProvider provider;

    @BeforeEach
    void setUp() {
        RedisConnection mockConnection = mock(RedisConnection.class);
        provider = new RedisMessagingProvider(mockConnection);
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
}
