package fr.codinbox.echo.api.exception.resource;

import fr.codinbox.echo.api.exception.UnknownResourceException;
import org.jetbrains.annotations.NotNull;

public class UnknownProxyException extends UnknownResourceException {

    public UnknownProxyException(final @NotNull String name) {
        super("proxy", name);
    }

    public UnknownProxyException() {
        super("proxy", "");
    }

}
