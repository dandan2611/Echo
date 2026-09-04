package fr.codinbox.echo.api.server;

import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/** Publishes and reads the local server's load snapshot. */
public interface ServerLoadManager {

    /**
     * Installs the single third-party provider override.
     * Closing the returned handle restores Echo's default provider.
     */
    @NotNull ProviderRegistration setProvider(@NotNull ServerLoadProvider provider);

    /** Samples the active provider and persists a fresh snapshot. */
    @NotNull EchoFuture<@NotNull ServerLoadSnapshot> refresh();

    /** Reads the last successfully persisted snapshot. */
    @NotNull EchoFuture<@NotNull Optional<ServerLoadSnapshot>> getCurrent();

    interface ProviderRegistration extends AutoCloseable {

        @Override
        void close();
    }
}
