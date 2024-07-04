package fr.codinbox.echo.api.exception;

import org.jetbrains.annotations.NotNull;

public class UnknownResourceException extends RuntimeException {

    public UnknownResourceException(final @NotNull String resourceType, final @NotNull String name) {
        super("Resource '" + resourceType + "' with name '" + name + "' has not been found");
    }

}
