package fr.codinbox.echo.api.id;

import org.jetbrains.annotations.CheckReturnValue;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public interface Identifiable<T> {

    @NotNull T getId();

    @CheckReturnValue
    @NotNull CompletableFuture<Instant> getCreationTime();

}
