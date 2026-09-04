package fr.codinbox.echo.api.server;

import org.jetbrains.annotations.NotNull;

/** Supplies the authoritative game participant load from the server thread. */
@FunctionalInterface
public interface ServerLoadProvider {

    @NotNull ServerLoad getLoad();
}
