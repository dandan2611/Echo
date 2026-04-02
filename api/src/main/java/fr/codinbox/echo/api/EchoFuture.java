package fr.codinbox.echo.api;

import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * A {@link CompletableFuture} extension that provides a convenient blocking {@link #await()} method.
 * <p>
 * This eliminates the need for separate async and blocking method variants in the API.
 * Callers can choose their execution model:
 * <pre>{@code
 * // Async
 * client.getUserById(uuid).thenAccept(user -> ...);
 *
 * // Blocking
 * Optional<User> user = client.getUserById(uuid).await();
 * }</pre>
 *
 * @param <T> the result type
 */
public class EchoFuture<T> extends CompletableFuture<T> {

    @Override
    public <U> @NotNull CompletableFuture<U> newIncompleteFuture() {
        return new EchoFuture<>();
    }

    /**
     * Blocks until the future completes and returns the result.
     *
     * @return the result
     */
    @Blocking
    public T await() {
        return this.join();
    }

    /**
     * Wraps an existing {@link CompletableFuture} into an {@link EchoFuture}.
     *
     * @param future the future to wrap
     * @param <T> the result type
     * @return an {@link EchoFuture} that completes when the given future completes
     */
    public static <T> @NotNull EchoFuture<T> of(final @NotNull CompletableFuture<T> future) {
        if (future instanceof EchoFuture<T> echoFuture)
            return echoFuture;
        final EchoFuture<T> echoFuture = new EchoFuture<>();
        future.whenComplete((result, error) -> {
            if (error != null) echoFuture.completeExceptionally(error);
            else echoFuture.complete(result);
        });
        return echoFuture;
    }

    /**
     * Creates an already completed {@link EchoFuture} with the given value.
     *
     * @param value the value
     * @param <T> the result type
     * @return a completed {@link EchoFuture}
     */
    public static <T> @NotNull EchoFuture<T> completed(final T value) {
        final EchoFuture<T> future = new EchoFuture<>();
        future.complete(value);
        return future;
    }

}
