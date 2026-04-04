package fr.codinbox.echo.core.messaging.provider;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessageHandler;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.messaging.Subscription;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RTopic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class RedisMessagingProvider implements MessagingProvider {

    private final @NotNull RedisConnection connection;

    private final @NotNull Map<UUID, Function<@NotNull EchoMessage, @NotNull Boolean>> messageReplyConsumers;
    private final @NotNull Map<String, List<MessageHandler<EchoMessage>>> messageHandlers;
    private final @NotNull List<String> localSubscriptions;

    public RedisMessagingProvider(final @NotNull RedisConnection connection) {
        this.connection = connection;
        this.messageReplyConsumers = new Object2ObjectOpenHashMap<>();
        this.messageHandlers = new Object2ObjectOpenHashMap<>();
        this.localSubscriptions = new ArrayList<>();
    }

    @Override
    public @NotNull CompletableFuture<Void> init() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NotNull CompletableFuture<Void> shutdown() {
        this.messageHandlers.clear();
        this.messageReplyConsumers.clear();
        this.localSubscriptions.clear();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NotNull <T extends EchoMessage> EchoFuture<Void> publish(@NotNull String t, @NotNull T obj) {
        final RTopic topic = this.connection.getClient().getTopic(t);
        return EchoFuture.of(topic.publishAsync(obj).toCompletableFuture().thenApply(v -> null));
    }

    @Override
    public void waitForReply(@NotNull EchoMessage message, @NotNull Function<@NotNull EchoMessage, @NotNull Boolean> consumer) {
        this.messageReplyConsumers.put(message.getMessageId(), consumer);
    }

    @Override
    public @NotNull Subscription subscribe(@NotNull String topic, @NotNull MessageHandler<EchoMessage> handler) {
        this.messageHandlers.computeIfAbsent(topic, t -> new ArrayList<>()).add(handler);
        if (!this.localSubscriptions.contains(topic)) {
            this.connection.getClient().getTopic(topic).addListener(EchoMessage.class, (channel, msg) -> {
                final List<MessageHandler<EchoMessage>> handlers = this.messageHandlers.get(topic);
                if (handlers != null) {
                    for (MessageHandler<EchoMessage> messageHandler : handlers) {
                        messageHandler.onReceive(msg);
                    }
                }
            });
            this.localSubscriptions.add(topic);
        }
        return new RedisSubscription(topic, handler);
    }

    @Override
    public boolean handleReply(@NotNull EchoMessage message) {
        final Function<@NotNull EchoMessage, @NotNull Boolean> consumer = this.messageReplyConsumers.get(message.getMessageId());
        try {
            if (consumer != null)
                return consumer.apply(message);
        } catch (Exception e) {
            e.printStackTrace();
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
            final List<MessageHandler<EchoMessage>> handlers = messageHandlers.get(this.topic);
            if (handlers != null) {
                handlers.remove(this.handler);
                if (handlers.isEmpty()) {
                    messageHandlers.remove(this.topic);
                    localSubscriptions.remove(this.topic);
                    return connection.getClient().getTopic(this.topic)
                            .removeAllListenersAsync().toCompletableFuture().thenApply(v -> null);
                }
            }
            return CompletableFuture.completedFuture(null);
        }

    }

}
