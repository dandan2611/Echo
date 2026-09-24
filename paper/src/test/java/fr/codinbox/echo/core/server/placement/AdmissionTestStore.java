package fr.codinbox.echo.core.server.placement;

import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.mockito.Mockito.mock;

/** Deterministic adapter at the placement transaction seam for Paper lifecycle tests. */
public final class AdmissionTestStore implements PlacementStore {
    private final Map<String, Object> values = new HashMap<>();
    private final Map<String, String> leases = new HashMap<>();
    public volatile Runnable before = () -> { };
    public volatile Runnable after = () -> { };

    public RedisServerPlacement placement() {
        final var placement = new RedisServerPlacement(mock(RedissonClient.class), this);
        placement.startAdmissionPublisher("server");
        return placement;
    }

    public synchronized Object value(String key) { return values.get(key); }

    @Override
    public synchronized <T> T execute(Duration budget, Function<Transaction, T> decision) {
        before.run();
        final Map<String, Object> pending = new HashMap<>(values);
        final Map<String, String> reservations = new HashMap<>(leases);
        final Instant now = Instant.now();
        final T result = decision.apply(new Transaction() {
            public Instant now() { return now; }
            public Object value(String key) { return pending.get(key); }
            public boolean alive(String key) { return true; }
            public void set(String key, Object value) { pending.put(key, value); }
            public Set<String> serverIds() { return Set.of("server"); }
            public void registerServer(String id, Instant at) { }
            public Map<String, String> reservations() { return reservations; }
            public void validUntil(Instant deadline) { }
        });
        values.clear(); values.putAll(pending);
        leases.clear(); leases.putAll(reservations);
        after.run();
        return result;
    }
}
