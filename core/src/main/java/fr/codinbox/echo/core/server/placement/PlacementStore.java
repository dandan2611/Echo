package fr.codinbox.echo.core.server.placement;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Internal transaction seam: decisions and their observations commit together. */
interface PlacementStore {
    <T> T execute(Duration budget, Function<Transaction, T> decision);

    interface Transaction {
        Instant now();
        /** Authoritative lease clock, independent of application telemetry timestamps. */
        default Instant leaseNow() { return now(); }
        default void leaseValidUntil(Instant deadline) { validUntil(deadline); }
        Object value(String key);
        boolean alive(String key);
        void set(String key, Object value);
        void registerServer(String serverId, Instant createdAt);
        Set<String> serverIds();
        Map<String, String> reservations();
        void validUntil(Instant deadline);
    }
}
