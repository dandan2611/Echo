package fr.codinbox.echo.api.messaging.impl;

import fr.codinbox.echo.api.messaging.EchoMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.json.JSONComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class SendMessage extends EchoMessage {

    private @NotNull String serialized;
    private @NotNull UUID[] targets;

    public SendMessage() {
    }

    public SendMessage(@NotNull UUID[] targets, @NotNull String serialized) {
        this.serialized = serialized;
        this.targets = targets;
    }

    public SendMessage(@NotNull UUID[] targets, final @NotNull Component component) {
        this.targets = targets;
        this.serialized = JSONComponentSerializer.json().serialize(component);
    }

    public @NotNull String getSerialized() {
        return serialized;
    }

    public void setSerialized(final @NotNull String serialized) {
        this.serialized = serialized;
    }

    public @NotNull UUID[] getTargets() {
        return targets;
    }

    public void setTargets(@NotNull UUID[] targets) {
        this.targets = targets;
    }

    public @NotNull Component getComponent() {
        return JSONComponentSerializer.json().deserialize(this.serialized);
    }

}
