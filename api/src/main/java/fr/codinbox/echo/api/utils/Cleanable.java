package fr.codinbox.echo.api.utils;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * A cleanable resource is a resource that can be tear down without letting any resources behind.
 */
public interface Cleanable {

    /**
     * Cleans up the resource.
     *
     * @return a future that completes when the resource is cleaned up
     */
    @NotNull CompletableFuture<Void> cleanup();

}
