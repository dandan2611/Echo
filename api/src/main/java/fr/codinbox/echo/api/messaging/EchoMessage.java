package fr.codinbox.echo.api.messaging;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoFuture;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

@Setter
@Getter
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public abstract class EchoMessage {

    private @NotNull UUID messageId = UUID.randomUUID();

    private @Nullable String replyTopic = null;

    public EchoMessage() {
        try {
            this.replyTopic = Echo.getClient().getLocalTopic();
        } catch (Exception ignored) {
        }
    }

    public <T extends EchoMessage> @NotNull EchoFuture<Void> reply(final @NotNull T response) {
        if (this.getReplyTopic() == null) {
            throw new IllegalStateException("Cannot reply to a message that does not wait for a reply");
        }

        return this.reply(
                this.getReplyTopic(),
                response,
                Echo.getClient().getCurrentResourceId().orElse(null)
        );
    }

    public <T extends EchoMessage> @NotNull EchoFuture<Void> reply(final @NotNull String topic,
                                                                     final @NotNull T response,
                                                                     final @Nullable String replyTopic) {
        Objects.requireNonNull(topic);
        Objects.requireNonNull(response);

        response.setReplyTopic(replyTopic);
        response.setMessageId(this.getMessageId());
        return Echo.getClient().getMessagingProvider().publish(topic, response);
    }

    public void onReply(final @NotNull Function<@NotNull EchoMessage, @NotNull Boolean> consumer) {
        Echo.getClient().getMessagingProvider().waitForReply(this, consumer);
    }

}
