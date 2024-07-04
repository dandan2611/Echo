package fr.codinbox.echo.api.exception.resource;

import fr.codinbox.echo.api.exception.UnknownResourceException;
import org.jetbrains.annotations.NotNull;

public class UnknownUserException extends UnknownResourceException {

    public UnknownUserException(final @NotNull String name) {
        super("user", name);
    }

}
