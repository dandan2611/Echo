package fr.codinbox.echo.core.id;

import fr.codinbox.echo.api.id.Identifiable;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public abstract class IdentifiableImpl<T> implements Identifiable<T> {

    private final @NotNull T id;

    public IdentifiableImpl(final @NotNull T id) {
        this.id = id;
    }

    @Override
    public @NotNull T getId() {
        return this.id;
    }

    @Override
    public abstract @NotNull CompletableFuture<@NotNull Optional<Long>> getCreationTimeAsync();

    @Blocking
    public @NotNull Optional<Long> getCreationTime() {
        return this.getCreationTimeAsync().join();
    }

}
