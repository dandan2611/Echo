package fr.codinbox.echo.api.exception.resource;

import fr.codinbox.echo.api.exception.UnknownResourceException;
import org.jetbrains.annotations.NotNull;

public class UnknownServerException extends UnknownResourceException {

    public UnknownServerException(final @NotNull String name) {
        super("server", name);
    }

}
