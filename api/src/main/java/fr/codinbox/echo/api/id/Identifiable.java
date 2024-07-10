package fr.codinbox.echo.api.id;

import org.jetbrains.annotations.CheckReturnValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

public interface Identifiable<T> {

    @NotNull T getId();

    @CheckReturnValue
    @NotNull CompletableFuture<@Nullable Long> getCreationTime();

}
