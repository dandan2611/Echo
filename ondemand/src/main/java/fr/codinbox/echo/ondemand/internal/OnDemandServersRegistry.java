package fr.codinbox.echo.ondemand.internal;

import fr.codinbox.echo.ondemand.OnDemandServers;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicReference;

/** Internal process-wide registry backing {@link OnDemandServers#load()}. */
@ApiStatus.Internal
public final class OnDemandServersRegistry {

    private static final AtomicReference<OnDemandServers> SERVERS = new AtomicReference<>();

    private OnDemandServersRegistry() {
    }

    public static @NotNull OnDemandServers load() {
        final OnDemandServers servers = SERVERS.get();
        if (servers == null)
            throw new IllegalStateException("OnDemandServers is not loaded");
        return servers;
    }

    public static void register(final @NotNull OnDemandServers servers) {
        if (!SERVERS.compareAndSet(null, servers))
            throw new IllegalStateException("OnDemandServers is already loaded");
    }

    public static void unregister(final @NotNull OnDemandServers servers) {
        SERVERS.compareAndSet(servers, null);
    }
}
