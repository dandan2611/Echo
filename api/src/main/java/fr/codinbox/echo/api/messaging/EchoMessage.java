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
import java.util.function.Consumer;
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

    /**
     * Registers a typed reply handler. Only replies matching the given type will be dispatched.
     *
     * @param type the expected reply type
     * @param consumer the handler to invoke
     * @param <T> the reply message type
     */
    public <T extends EchoMessage> void onReply(final @NotNull Class<T> type,
                                                final @NotNull Consumer<@NotNull T> consumer) {
        this.onReply(msg -> {
            if (type.isInstance(msg)) {
                consumer.accept(type.cast(msg));
                return true;
            }
            return false;
        });
    }

    /**
     * Returns a future that completes when a reply of the given type is received.
     * Useful for request/response patterns.
     *
     * <pre>{@code
     * request.awaitReply(ServerSwitchRequest.Response.class)
     *        .thenAccept(resp -> handle(resp));
     * }</pre>
     *
     * @param type the expected reply type
     * @param <T> the reply message type
     * @return a future that completes with the reply
     */
    public <T extends EchoMessage> @NotNull EchoFuture<T> awaitReply(final @NotNull Class<T> type) {
        final EchoFuture<T> future = new EchoFuture<>();
        this.onReply(msg -> {
            if (type.isInstance(msg)) {
                future.complete(type.cast(msg));
                return true;
            }
            return false;
        });
        return future;
    }

    /**
     * Sends this message to the given target.
     *
     * @param target the target to send to
     * @return a future that completes when the message is sent
     */
    public @NotNull EchoFuture<Void> sendTo(final @NotNull MessageTarget target) {
        return Echo.getClient().getMessagingProvider().publishAll(target.getTargets(), this);
    }

    /**
     * Sends this message to a single server.
     *
     * @param serverId the server identifier
     * @return a future that completes when the message is sent
     */
    public @NotNull EchoFuture<Void> sendToServer(final @NotNull String serverId) {
        return this.sendTo(MessageTarget.server(serverId));
    }

    /**
     * Sends this message to a single proxy.
     *
     * @param proxyId the proxy identifier
     * @return a future that completes when the message is sent
     */
    public @NotNull EchoFuture<Void> sendToProxy(final @NotNull String proxyId) {
        return this.sendTo(MessageTarget.proxy(proxyId));
    }

}
