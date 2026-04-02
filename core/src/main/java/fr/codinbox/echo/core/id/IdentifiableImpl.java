package fr.codinbox.echo.core.id;

import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.id.Identifiable;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

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
    public abstract @NotNull EchoFuture<@NotNull Optional<Long>> getCreationTime();

}
