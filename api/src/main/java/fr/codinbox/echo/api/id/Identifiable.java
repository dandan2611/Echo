package fr.codinbox.echo.api.id;

import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface Identifiable<T> {

    @NotNull T getId();

    @NotNull CompletableFuture<@NotNull Optional<Long>> getCreationTimeAsync();

    /**
     * Get the creation time of this resource.
     *
     * @return the creation time of this resource, or {@link Optional#empty()} if the creation time is not available
     */
    @Blocking
    default @NotNull Optional<Long> getCreationTime() {
        return this.getCreationTimeAsync().join();
    }

}
