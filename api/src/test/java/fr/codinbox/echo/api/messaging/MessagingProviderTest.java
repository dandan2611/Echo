package fr.codinbox.echo.api.messaging;

import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class MessagingProviderTest {

    @Test
    void request_whenProviderDoesNotSupportIt_failsWithoutBreakingImplementations() {
        MessagingProvider provider = new LegacyMessagingProvider();

        assertThatThrownBy(() -> provider.request(
                "topic", new TestMessage(), TestMessage.class, Duration.ofSeconds(1)).join())
                .hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    private static final class LegacyMessagingProvider implements MessagingProvider {

        @Override
        public @NotNull CompletableFuture<Void> init() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public @NotNull CompletableFuture<Void> shutdown() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T extends EchoMessage> @NotNull EchoFuture<Void> publish(
                @NotNull String topic, @NotNull T obj) {
            return EchoFuture.completed(null);
        }

        @Override
        public void waitForReply(@NotNull EchoMessage message,
                                 @NotNull Function<@NotNull EchoMessage, @NotNull Boolean> consumer) {
        }

        @Override
        public @NotNull Subscription subscribe(
                @NotNull String topic, @NotNull MessageHandler<EchoMessage> handler) {
            return new Subscription() {
                @Override
                public @NotNull String getTopic() {
                    return topic;
                }

                @Override
                public @NotNull MessageHandler<?> getHandler() {
                    return handler;
                }

                @Override
                public @NotNull CompletableFuture<Void> cancel() {
                    return CompletableFuture.completedFuture(null);
                }
            };
        }

        @Override
        public boolean handleReply(@NotNull EchoMessage message) {
            return false;
        }
    }

    private static final class TestMessage extends EchoMessage {
    }
}
