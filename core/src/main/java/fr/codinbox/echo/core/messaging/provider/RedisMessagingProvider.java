package fr.codinbox.echo.core.messaging.provider;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessageHandler;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.messaging.Subscription;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RTopic;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public class RedisMessagingProvider implements MessagingProvider {

    private final @NotNull RedisConnection connection;

    private final @NotNull Map<UUID, Function<@NotNull EchoMessage, @NotNull Boolean>> messageReplyConsumers;
    private final @NotNull Map<String, List<MessageHandler<EchoMessage>>> messageHandlers;
    private final @NotNull Map<String, Integer> localSubscriptions;

    public RedisMessagingProvider(final @NotNull RedisConnection connection) {
        this.connection = connection;
        this.messageReplyConsumers = new ConcurrentHashMap<>();
        this.messageHandlers = new ConcurrentHashMap<>();
        this.localSubscriptions = new ConcurrentHashMap<>();
    }

    @Override
    public @NotNull CompletableFuture<Void> init() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NotNull CompletableFuture<Void> shutdown() {
        final List<CompletableFuture<?>> removals = new ArrayList<>();
        this.localSubscriptions.forEach((topic, listenerId) -> removals.add(
                this.connection.getClient().getTopic(topic).removeListenerAsync(listenerId).toCompletableFuture()));
        this.messageHandlers.clear();
        this.messageReplyConsumers.clear();
        this.localSubscriptions.clear();
        return CompletableFuture.allOf(removals.toArray(CompletableFuture[]::new));
    }

    @Override
    public @NotNull <T extends EchoMessage> EchoFuture<Void> publish(@NotNull String t, @NotNull T obj) {
        final RTopic topic = this.connection.getClient().getTopic(t);
        return EchoFuture.of(topic.publishAsync(obj).toCompletableFuture().thenApply(v -> null));
    }

    @Override
    public @NotNull <R extends EchoMessage> EchoFuture<R> request(
            final @NotNull String topic,
            final @NotNull EchoMessage request,
            final @NotNull Class<R> responseType,
            final @NotNull Duration timeout) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(responseType, "responseType");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.toMillis() == 0)
            throw new IllegalArgumentException("timeout must be at least one millisecond");
        if (request.getReplyTopic() == null)
            throw new IllegalStateException("Request has no reply topic");

        final EchoFuture<R> result = new EchoFuture<>();
        final Function<EchoMessage, Boolean> waiter = reply -> {
            if (!responseType.isInstance(reply))
                return false;
            result.complete(responseType.cast(reply));
            return true;
        };
        if (this.messageReplyConsumers.putIfAbsent(request.getMessageId(), waiter) != null)
            throw new IllegalStateException("A reply waiter already exists for " + request.getMessageId());

        result.orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((ignored, error) ->
                        this.messageReplyConsumers.remove(request.getMessageId(), waiter));
        this.publish(topic, request).whenComplete((ignored, error) -> {
            if (error != null)
                result.completeExceptionally(error);
        });
        return result;
    }

    @Override
    public void waitForReply(@NotNull EchoMessage message, @NotNull Function<@NotNull EchoMessage, @NotNull Boolean> consumer) {
        this.messageReplyConsumers.put(message.getMessageId(), consumer);
    }

    @Override
    public synchronized @NotNull Subscription subscribe(@NotNull String topic, @NotNull MessageHandler<EchoMessage> handler) {
        this.messageHandlers.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(handler);
        this.localSubscriptions.computeIfAbsent(topic, ignored ->
            this.connection.getClient().getTopic(topic).addListener(EchoMessage.class, (channel, msg) -> {
                final List<MessageHandler<EchoMessage>> handlers = this.messageHandlers.get(topic);
                if (handlers != null) {
                    for (MessageHandler<EchoMessage> messageHandler : handlers) {
                        messageHandler.onReceive(msg);
                    }
                }
            }));
        return new RedisSubscription(topic, handler);
    }

    @Override
    public boolean handleReply(@NotNull EchoMessage message) {
        final Function<@NotNull EchoMessage, @NotNull Boolean> consumer =
                this.messageReplyConsumers.get(message.getMessageId());
        try {
            if (consumer != null) {
                final boolean accepted = consumer.apply(message);
                if (accepted)
                    this.messageReplyConsumers.remove(message.getMessageId(), consumer);
                return accepted;
            }
        } catch (Exception e) {
            this.messageReplyConsumers.remove(message.getMessageId(), consumer);
        }
        return true;
    }

    private class RedisSubscription implements Subscription {

        private final @NotNull String topic;
        private final @NotNull MessageHandler<EchoMessage> handler;

        private RedisSubscription(final @NotNull String topic,
                                  final @NotNull MessageHandler<EchoMessage> handler) {
            this.topic = topic;
            this.handler = handler;
        }

        @Override
        public @NotNull String getTopic() {
            return this.topic;
        }

        @Override
        public @NotNull MessageHandler<?> getHandler() {
            return this.handler;
        }

        @Override
        public @NotNull CompletableFuture<Void> cancel() {
            synchronized (RedisMessagingProvider.this) {
                final List<MessageHandler<EchoMessage>> handlers = messageHandlers.get(this.topic);
                if (handlers != null) {
                    handlers.remove(this.handler);
                    if (handlers.isEmpty()) {
                        messageHandlers.remove(this.topic);
                        final Integer listenerId = localSubscriptions.remove(this.topic);
                        if (listenerId != null)
                            return connection.getClient().getTopic(this.topic)
                                    .removeListenerAsync(listenerId).toCompletableFuture();
                    }
                }
            }
            return CompletableFuture.completedFuture(null);
        }

    }

}
