package fr.codinbox.echo.core.server;

import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerLoadManager;
import fr.codinbox.echo.api.server.ServerLoadProvider;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class ServerLoadManagerImpl implements ServerLoadManager {

    private final @NotNull Server server;
    private final @Nullable ServerLoadProvider defaultProvider;
    private final @NotNull Duration staleAfter;
    private final @NotNull Clock clock;
    private Registration override;

    public ServerLoadManagerImpl(
            final @NotNull Server server,
            final @Nullable ServerLoadProvider defaultProvider,
            final @NotNull Duration staleAfter) {
        this(server, defaultProvider, staleAfter, Clock.systemUTC());
    }

    ServerLoadManagerImpl(
            final @NotNull Server server,
            final @Nullable ServerLoadProvider defaultProvider,
            final @NotNull Duration staleAfter,
            final @NotNull Clock clock) {
        this.server = server;
        this.defaultProvider = defaultProvider;
        this.staleAfter = staleAfter;
        this.clock = clock;
        if (staleAfter.isZero() || staleAfter.isNegative())
            throw new IllegalArgumentException("staleAfter must be positive");
    }

    @Override
    public synchronized @NotNull ProviderRegistration setProvider(final @NotNull ServerLoadProvider provider) {
        if (this.override != null)
            throw new IllegalStateException("A server load provider override is already registered");
        this.override = new Registration(provider);
        return this.override;
    }

    @Override
    public @NotNull EchoFuture<@NotNull ServerLoadSnapshot> refresh() {
        final ServerLoadProvider provider;
        synchronized (this) {
            provider = this.override != null ? this.override.provider : this.defaultProvider;
        }
        if (provider == null)
            return failedFuture(new IllegalStateException("No server load provider is registered"));

        final ServerLoadSnapshot snapshot;
        try {
            final Instant now = this.clock.instant();
            snapshot = new ServerLoadSnapshot(provider.getLoad(), now, now.plus(this.staleAfter));
        } catch (RuntimeException error) {
            return failedFuture(error);
        }
        return EchoFuture.of(this.server.setProperty(Server.PROPERTY_LOAD, snapshot)
                .thenApply(ignored -> snapshot));
    }

    @Override
    public @NotNull EchoFuture<@NotNull Optional<ServerLoadSnapshot>> getCurrent() {
        return this.server.getLoad();
    }

    private static <T> EchoFuture<T> failedFuture(final Throwable error) {
        return EchoFuture.of(java.util.concurrent.CompletableFuture.failedFuture(error));
    }

    private final class Registration implements ProviderRegistration {

        private final ServerLoadProvider provider;

        private Registration(final ServerLoadProvider provider) {
            this.provider = provider;
        }

        @Override
        public void close() {
            synchronized (ServerLoadManagerImpl.this) {
                if (ServerLoadManagerImpl.this.override == this)
                    ServerLoadManagerImpl.this.override = null;
            }
        }
    }
}
