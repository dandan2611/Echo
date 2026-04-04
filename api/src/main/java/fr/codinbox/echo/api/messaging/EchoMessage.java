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

/**
 * Base class for all messages sent through the Echo messaging system.
 *
 * <p>To create a custom message type, extend this class and add your fields.
 * A no-arg constructor is required for Jackson deserialization:</p>
 *
 * <pre>{@code
 * public class AlertMessage extends EchoMessage {
 *     private String text;
 *
 *     public AlertMessage() {} // Required for deserialization
 *
 *     public AlertMessage(String text) {
 *         this.text = text;
 *     }
 *
 *     public String getText() { return text; }
 * }
 * }</pre>
 *
 * <p>Messages can be sent using convenience methods:</p>
 * <pre>{@code
 * // Send to a specific server or proxy
 * new AlertMessage("Hello!").sendToServer("lobby-1");
 * new AlertMessage("Hello!").sendToProxy("proxy-eu");
 *
 * // Send to a custom target
 * MessageTarget target = MessageTarget.servers("lobby-1", "lobby-2");
 * new AlertMessage("Hello!").sendTo(target);
 *
 * // Broadcast to the entire network
 * new AlertMessage("Broadcast!").sendTo(MessageTarget.everyone());
 * }</pre>
 *
 * <p>Messages also support request/response patterns via {@link #reply(EchoMessage)},
 * {@link #onReply(Class, Consumer)}, and {@link #awaitReply(Class)}.</p>
 *
 * @see MessageTarget
 * @see MessagingProvider
 */
@Setter
@Getter
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public abstract class EchoMessage {

    /**
     * Unique identifier for this message instance. Used to correlate replies with their requests.
     */
    private @NotNull UUID messageId = UUID.randomUUID();

    /**
     * The topic that replies to this message should be sent to.
     * Automatically set to the local node's topic on construction.
     * {@code null} if this message does not expect a reply.
     */
    private @Nullable String replyTopic = null;

    /**
     * Creates a new message. The {@link #replyTopic} is automatically set to the
     * current node's local topic, enabling request/response patterns.
     */
    public EchoMessage() {
        try {
            this.replyTopic = Echo.getClient().getLocalTopic();
        } catch (Exception ignored) {
        }
    }

    /**
     * Sends a reply to this message back to the original sender.
     *
     * <p>The reply is sent to the {@link #replyTopic} of this message, which is the
     * topic of the node that originally sent it. The reply carries the same
     * {@link #messageId} so the sender can correlate it.</p>
     *
     * <pre>{@code
     * // On the receiving side
     * messaging.subscribe("my-topic", MyRequest.class, request -> {
     *     MyResponse response = new MyResponse("ok");
     *     request.reply(response);
     * });
     * }</pre>
     *
     * @param response the reply message
     * @param <T>      the reply message type
     * @return a future that completes when the reply is sent
     * @throws IllegalStateException if this message has no reply topic (was not expecting a reply)
     */
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

    /**
     * Sends a reply to a specific topic with a custom reply topic.
     *
     * <p>This is a lower-level method. Prefer {@link #reply(EchoMessage)} for standard
     * request/response patterns.</p>
     *
     * @param topic      the topic to send the reply to
     * @param response   the reply message
     * @param replyTopic the reply topic to set on the response (for chaining replies)
     * @param <T>        the reply message type
     * @return a future that completes when the reply is sent
     */
    public <T extends EchoMessage> @NotNull EchoFuture<Void> reply(final @NotNull String topic,
                                                                     final @NotNull T response,
                                                                     final @Nullable String replyTopic) {
        Objects.requireNonNull(topic);
        Objects.requireNonNull(response);

        response.setReplyTopic(replyTopic);
        response.setMessageId(this.getMessageId());
        return Echo.getClient().getMessagingProvider().publish(topic, response);
    }

    /**
     * Registers a raw reply handler for this message.
     *
     * <p>The consumer function receives every reply and returns {@code true} to accept it
     * (and stop listening) or {@code false} to keep waiting. Prefer
     * {@link #onReply(Class, Consumer)} for typed handling.</p>
     *
     * @param consumer a function that receives reply messages and returns {@code true} to accept
     */
    public void onReply(final @NotNull Function<@NotNull EchoMessage, @NotNull Boolean> consumer) {
        Echo.getClient().getMessagingProvider().waitForReply(this, consumer);
    }

    /**
     * Registers a typed reply handler for this message.
     *
     * <p>Only replies matching the given type will be dispatched to the handler.
     * The handler is automatically removed after the first matching reply.</p>
     *
     * <pre>{@code
     * request.sendToServer("lobby-1");
     * request.onReply(MyResponse.class, response -> {
     *     System.out.println("Got response: " + response.getResult());
     * });
     * }</pre>
     *
     * @param type     the expected reply type class
     * @param consumer the handler to invoke when a matching reply is received
     * @param <T>      the reply message type
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
     *
     * <p>This is the most convenient way to implement request/response patterns.
     * The future completes with the first reply message that matches the expected type.</p>
     *
     * <pre>{@code
     * MyRequest request = new MyRequest("data");
     * request.sendToServer("lobby-1");
     *
     * // Async
     * request.awaitReply(MyResponse.class).thenAccept(response -> {
     *     System.out.println("Got: " + response.getResult());
     * });
     *
     * // Blocking
     * MyResponse response = request.awaitReply(MyResponse.class).await();
     * }</pre>
     *
     * @param type the expected reply type class
     * @param <T>  the reply message type
     * @return a future that completes with the first matching reply
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
     * <pre>{@code
     * MessageTarget target = MessageTarget.servers("lobby-1", "lobby-2");
     * new AlertMessage("Hello!").sendTo(target);
     *
     * // Broadcast to everyone
     * new AlertMessage("Hello!").sendTo(MessageTarget.everyone());
     * }</pre>
     *
     * @param target the target to send to
     * @return a future that completes when the message is sent to all targets
     */
    public @NotNull EchoFuture<Void> sendTo(final @NotNull MessageTarget target) {
        return Echo.getClient().getMessagingProvider().publishAll(target.getTargets(), this);
    }

    /**
     * Sends this message to a single server.
     *
     * <p>Shorthand for {@code sendTo(MessageTarget.server(serverId))}.</p>
     *
     * <pre>{@code
     * new AlertMessage("Hello!").sendToServer("lobby-1");
     * }</pre>
     *
     * @param serverId the target server identifier
     * @return a future that completes when the message is sent
     */
    public @NotNull EchoFuture<Void> sendToServer(final @NotNull String serverId) {
        return this.sendTo(MessageTarget.server(serverId));
    }

    /**
     * Sends this message to a single proxy.
     *
     * <p>Shorthand for {@code sendTo(MessageTarget.proxy(proxyId))}.</p>
     *
     * <pre>{@code
     * new AlertMessage("Hello!").sendToProxy("proxy-eu");
     * }</pre>
     *
     * @param proxyId the target proxy identifier
     * @return a future that completes when the message is sent
     */
    public @NotNull EchoFuture<Void> sendToProxy(final @NotNull String proxyId) {
        return this.sendTo(MessageTarget.proxy(proxyId));
    }

}
