package fr.codinbox.echo.api.id;

import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

public interface Identifiable<T> {

    @NotNull T getId();

    @NotNull EchoFuture<@NotNull Optional<Long>> getCreationTime();

}
