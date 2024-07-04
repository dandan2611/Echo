package fr.codinbox.echo.api.exception.user;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class UserHasNoProxyException extends RuntimeException {

    public UserHasNoProxyException(final @NotNull UUID userId) {
        super("User with id '" + userId + "' has no proxy, it could be caused by a disconnection from the network");
    }

}
