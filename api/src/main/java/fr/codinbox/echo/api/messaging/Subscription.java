package fr.codinbox.echo.api.messaging;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * A handle to an active topic subscription that can be cancelled.
 *
 * <p>Returned by {@link MessagingProvider#subscribe(String, MessageHandler)}. Call
 * {@link #cancel()} to unsubscribe from the topic.</p>
 *
 * @see MessagingProvider#subscribe(String, MessageHandler)
 */
public interface Subscription {

    /**
     * Gets the topic this subscription is listening on.
     *
     * @return the topic name
     */
    @NotNull String getTopic();

    /**
     * Gets the handler associated with this subscription.
     *
     * @return the message handler
     */
    @NotNull MessageHandler<?> getHandler();

    /**
     * Cancels this subscription, stopping message delivery.
     *
     * @return a future that completes when the subscription is fully cancelled
     */
    @NotNull CompletableFuture<Void> cancel();

}
