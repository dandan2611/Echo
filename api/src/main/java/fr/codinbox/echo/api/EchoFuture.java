package fr.codinbox.echo.api;

import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * A {@link CompletableFuture} extension that provides a convenient blocking {@link #await()} method.
 *
 * <p>All Echo API methods return {@code EchoFuture}, giving callers the choice between
 * asynchronous and blocking execution models:</p>
 *
 * <pre>{@code
 * // Asynchronous - non-blocking, runs callback on completion
 * client.getUserById(uuid).thenAccept(userOpt -> {
 *     userOpt.ifPresent(user -> System.out.println("Found: " + user.getId()));
 * });
 *
 * // Blocking - waits for the result
 * Optional<User> user = client.getUserById(uuid).await();
 *
 * // Chaining - compose multiple async operations
 * client.getUserById(uuid)
 *     .thenCompose(opt -> opt.map(User::getCurrentServer)
 *         .orElse(EchoFuture.completed(Optional.empty())))
 *     .thenAccept(serverOpt -> serverOpt.ifPresent(s ->
 *         System.out.println("Player is on: " + s.getId())
 *     ));
 * }</pre>
 *
 * @param <T> the result type
 * @see CompletableFuture
 */
public class EchoFuture<T> extends CompletableFuture<T> {

    @Override
    public <U> @NotNull CompletableFuture<U> newIncompleteFuture() {
        return new EchoFuture<>();
    }

    /**
     * Blocks the current thread until the future completes and returns the result.
     *
     * <p>This is a convenience wrapper around {@link CompletableFuture#join()}.
     * If the future completed exceptionally, the exception is rethrown wrapped
     * in a {@link java.util.concurrent.CompletionException}.</p>
     *
     * <pre>{@code
     * Optional<User> user = client.getUserById(uuid).await();
     * Map<String, Long> servers = client.getServers().await();
     * }</pre>
     *
     * @return the result value
     */
    @Blocking
    public T await() {
        return this.join();
    }

    /**
     * Wraps an existing {@link CompletableFuture} into an {@link EchoFuture}.
     *
     * <p>If the given future is already an {@code EchoFuture}, it is returned as-is.
     * Otherwise, a new {@code EchoFuture} is created that completes when the given future completes.</p>
     *
     * <pre>{@code
     * CompletableFuture<String> regularFuture = someAsyncOperation();
     * EchoFuture<String> echoFuture = EchoFuture.of(regularFuture);
     * String result = echoFuture.await();
     * }</pre>
     *
     * @param future the future to wrap
     * @param <T>    the result type
     * @return an {@code EchoFuture} that completes when the given future completes
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
     * Creates an already-completed {@link EchoFuture} with the given value.
     *
     * <p>Useful for returning immediate results in methods that must return a future:</p>
     *
     * <pre>{@code
     * EchoFuture<String> immediate = EchoFuture.completed("hello");
     * // immediate.await() returns "hello" instantly
     * }</pre>
     *
     * @param value the value to complete the future with (may be {@code null})
     * @param <T>   the result type
     * @return a completed {@code EchoFuture}
     */
    public static <T> @NotNull EchoFuture<T> completed(final T value) {
        final EchoFuture<T> future = new EchoFuture<>();
        future.complete(value);
        return future;
    }

}
