package fr.codinbox.echo.api.server;

import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.id.Identifiable;
import fr.codinbox.echo.api.messaging.MessageRouter;
import fr.codinbox.echo.api.property.PropertyHolder;
import fr.codinbox.echo.api.user.UserHolder;
import fr.codinbox.echo.api.utils.Cleanable;
import org.jetbrains.annotations.NotNull;

public interface Server extends Identifiable<String>, UserHolder, PropertyHolder, MessageRouter, Joinable, Cleanable {

    @NotNull EchoFuture<@NotNull Boolean> stillExists();

}
