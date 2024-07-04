package fr.codinbox.echo.api.utils;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public interface Cleanable {

    @NotNull CompletableFuture<Void> cleanup();

}
