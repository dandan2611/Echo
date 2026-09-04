package fr.codinbox.echo.paper.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Objects;

/** Notifies game plugins that this server must stop accepting work and shut down by a deadline. */
public final class ServerDrainEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Instant deadline;

    public ServerDrainEvent(final @NotNull Instant deadline) {
        this.deadline = Objects.requireNonNull(deadline, "deadline");
    }

    public @NotNull Instant getDeadline() {
        return this.deadline;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
