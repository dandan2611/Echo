package fr.codinbox.echo.commands;

import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/** Adapts a platform sender to Adventure output and a non-sensitive audit identity. */
public interface CommandAudience<S> {

    void send(S sender, Component message);

    String identity(S sender);

    default Optional<UUID> playerId(S sender) {
        return Optional.empty();
    }
}
