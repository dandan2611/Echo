package fr.codinbox.echo.core.utils;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public final class FutureUtils {

    public static <T> @NotNull CompletableFuture<T> completeAsync(final @NotNull CompletableFuture<T> future) {
        final CompletableFuture<T> result = new CompletableFuture<>();
        future.thenAcceptAsync(result::complete);
        return result;
    }

}
