package fr.codinbox.echo.core.messaging.provider;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessageHandler;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RTopic;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class RedisMessagingProvider implements MessagingProvider {

    private final @NotNull RedisConnection connection;

    private final @NotNull Map<UUID, Function<@NotNull EchoMessage, @NotNull Boolean>> messageReplyConsumers;
    private final @NotNull Map<String, List<MessageHandler>> messageHandlers;
    private final @NotNull List<String> localSubscriptions;

    public RedisMessagingProvider(final @NotNull RedisConnection connection) {
        this.connection = connection;
        this.messageReplyConsumers = new Object2ObjectOpenHashMap<>();
        this.messageHandlers = new Object2ObjectOpenHashMap<>();
        this.localSubscriptions = new ArrayList<>();
    }

    @Override
    public @NotNull <T extends EchoMessage> CompletableFuture<Void> publish(@NotNull String t, @NotNull T obj) {
        final RTopic topic = this.connection.getClient().getTopic(t);
        return topic.publishAsync(obj).toCompletableFuture().thenApply(v -> null);
    }

    @Override
    public void waitForReply(@NotNull EchoMessage message, @NotNull Function<@NotNull EchoMessage, @NotNull Boolean> consumer) {
        this.messageReplyConsumers.put(message.getMessageId(), consumer);
    }

    @Override
    public void subscribe(@NotNull String topic, @NotNull MessageHandler handler) {
        this.messageHandlers.computeIfAbsent(topic, t -> new ArrayList<>()).add(handler);
        if (this.localSubscriptions.contains(topic))
            return;
        this.connection.getClient().getTopic(topic).addListener(EchoMessage.class, (channel, msg) -> {
            for (MessageHandler messageHandler : this.messageHandlers.get(topic)) {
                messageHandler.onReceive(msg);
            }
        });
        this.localSubscriptions.add(topic);
    }

    @Override
    public boolean handleReply(@NotNull EchoMessage message) {
        final Function<@NotNull EchoMessage, @NotNull Boolean> consumer = this.messageReplyConsumers.get(message.getMessageId());
        if (consumer != null)
            return consumer.apply(message);
        // TODO: Remove reply after delay (handle multiple replies
        return true;
    }

}
