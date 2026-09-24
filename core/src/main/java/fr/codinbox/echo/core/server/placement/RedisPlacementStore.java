package fr.codinbox.echo.core.server.placement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.codinbox.echo.api.server.ServerAdmissionSnapshot;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.handler.State;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Optimistic serializable placement transactions for standalone/Sentinel Redis. */
final class RedisPlacementStore implements PlacementStore {
    static final String REVISION = "placement:v2:revision";
    static final String RESERVATIONS = "placement:v2:reservations";
    private static final String REGISTRY = "servers:map";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COMMIT = resource("commit.lua");
    private static final String SNAPSHOT = """
            local t = redis.call('TIME')
            return {t[1], t[2], redis.call('GET', KEYS[1]) or '',
                    redis.call('HGETALL', KEYS[2]), redis.call('HGETALL', KEYS[3])}
            """;
    private static final String READ = """
            local t = redis.call('TIME')
            return {redis.call('GET', KEYS[1]) or false, redis.call('PTTL', KEYS[1]), t[1], t[2]}
            """;
    private final RedissonClient client;
    private final Clock clock;

    RedisPlacementStore(RedissonClient client) {
        this(client, Clock.systemUTC());
    }

    RedisPlacementStore(RedissonClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public <T> T execute(Duration budget, Function<Transaction, T> decision) {
        final long started = System.nanoTime();
        final String operation = UUID.randomUUID().toString();
        final Map<String, T> results = new HashMap<>();
        for (int retry = 0; retry < 32; retry++) {
            if (System.nanoTime() - started >= budget.toNanos())
                break;
            final Session session = new Session();
            // Subtract the complete elapsed time, conservatively including the snapshot round trip.
            session.redisDeadline(session.redisNow.plusNanos(
                    Math.max(0, budget.toNanos() - (System.nanoTime() - started))));
            final T result = decision.apply(session);
            final String attempt = UUID.randomUUID().toString();
            results.put(attempt, result);
            final String committed = session.commit(operation, attempt);
            if (results.containsKey(committed))
                return results.get(committed);
            if (!"CONFLICT".equals(committed))
                throw new PlacementUnavailableException("Placement operation expired before confirmation");
        }
        throw new PlacementUnavailableException("Placement conflict budget exhausted");
    }

    private final class Session implements Transaction {
        private final List<Object> keys = new ArrayList<>(List.of(REVISION, RESERVATIONS, REGISTRY, ""));
        private final Map<String, Read> reads = new LinkedHashMap<>();
        private final Map<String, byte[]> writes = new LinkedHashMap<>();
        private final Map<String, Instant> registrations = new LinkedHashMap<>();
        private final Map<String, String> original;
        private final Map<String, String> reservations;
        private final Map<String, String> registry;
        private final String revision;
        private final Instant now;
        private final Instant redisNow;
        private Instant deadline = Instant.MAX;

        Session() {
            final List<?> data = script().eval(RScript.Mode.READ_ONLY, SNAPSHOT, RScript.ReturnType.MULTI,
                    List.of(REVISION, RESERVATIONS, REGISTRY));
            redisNow = time(data.get(0), data.get(1));
            now = clock.instant();
            revision = text(data.get(2));
            original = pairs((List<?>) data.get(3), false);
            reservations = new LinkedHashMap<>(original);
            registry = pairs((List<?>) data.get(4), true);
        }

        @Override public Instant now() { return now; }
        @Override public Instant leaseNow() { return redisNow; }
        @Override public void leaseValidUntil(Instant until) { redisDeadline(until); }
        @Override public Map<String, String> reservations() { return reservations; }
        @Override public void registerServer(String serverId, Instant createdAt) {
            registrations.put(serverId, createdAt);
        }
        @Override public void validUntil(Instant until) {
            // Snapshots are application-clock timestamps; Redis TTLs use Redis TIME.
            // Translate at the captured observation instead of assuming identical clocks.
            redisDeadline(redisNow.plus(Duration.between(now, until)));
        }
        private void redisDeadline(Instant until) {
            if (until.isBefore(deadline)) deadline = until;
        }

        @Override
        public Set<String> serverIds() {
            return registry.keySet().stream().map(key -> (String) decode(unbase64(key), true)).collect(Collectors.toSet());
        }

        @Override
        public Object value(String key) {
            final Read read = reads.computeIfAbsent(key, ignored -> {
                final List<?> data = script().eval(RScript.Mode.READ_ONLY, READ, RScript.ReturnType.MULTI, List.of(key));
                final byte[] raw = (byte[]) data.get(0);
                final long ttl = ((Number) data.get(1)).longValue();
                if (ttl >= 0) redisDeadline(time(data.get(2), data.get(3)).plusMillis(ttl));
                return new Read(raw, decode(raw, false), ttl);
            });
            final Object value = read.value();
            if (value instanceof ServerAdmissionSnapshot snapshot && !snapshot.isStale(now))
                validUntil(snapshot.validUntil());
            if (value instanceof ServerLoadSnapshot snapshot && !snapshot.isStale(now))
                validUntil(snapshot.validUntil());
            return value;
        }

        @Override public boolean alive(String key) {
            value(key);
            return reads.get(key).ttl() > 0;
        }

        @Override
        public void set(String key, Object value) {
            value(key); // Capture the expected value even for an otherwise blind write.
            writes.put(key, encode(value));
        }

        String commit(String operation, String attempt) {
            keys.set(3, "placement:v2:receipt:" + operation);
            final List<Object> arguments = new ArrayList<>();
            arguments.add(null);
            final List<Map<String, Object>> observations = new ArrayList<>();
            reads.forEach((key, read) -> {
                arguments.add(read.raw() == null ? new byte[0] : read.raw());
                observations.add(Map.of("key", index(key), "present", read.raw() != null, "value", arguments.size()));
            });
            final List<Map<String, Object>> changes = new ArrayList<>();
            writes.forEach((key, value) -> {
                arguments.add(value);
                changes.add(Map.of("key", index(key), "value", arguments.size()));
            });
            final List<List<Integer>> registryArguments = new ArrayList<>();
            registry.forEach((key, value) -> {
                arguments.add(unbase64(key));
                arguments.add(unbase64(value));
                registryArguments.add(List.of(arguments.size() - 1, arguments.size()));
            });
            final Map<String, Object> plan = new LinkedHashMap<>();
            final List<List<Integer>> registrationArguments = new ArrayList<>();
            registrations.forEach((id, createdAt) -> {
                arguments.add(encodeWith(id, client.getConfig().getCodec().getMapKeyEncoder()));
                arguments.add(encodeWith(createdAt.toEpochMilli(), client.getConfig().getCodec().getMapValueEncoder()));
                registrationArguments.add(List.of(arguments.size() - 1, arguments.size()));
            });
            plan.put("registrations", registrationArguments);
            plan.put("deadline", deadline.toEpochMilli());
            plan.put("revision", revision);
            plan.put("registry", registryArguments);
            plan.put("reads", observations);
            plan.put("writes", changes);
            plan.put("changed", !registrations.isEmpty() || !writes.isEmpty() || !original.equals(reservations));
            plan.put("removed", original.keySet().stream().filter(key -> !reservations.containsKey(key)).toList());
            plan.put("reservations", entries(reservations));
            plan.put("attempt", attempt);
            try {
                arguments.set(0, JSON.writeValueAsBytes(plan));
                return text(script().eval(RScript.Mode.READ_WRITE, COMMIT, RScript.ReturnType.VALUE,
                        keys, arguments.toArray()));
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Cannot encode placement commit", error);
            }
        }

        private int index(String key) {
            int index = keys.indexOf(key);
            if (index < 0) { index = keys.size(); keys.add(key); }
            return index + 1;
        }
    }

    private RScript script() { return client.getScript(ByteArrayCodec.INSTANCE); }

    private byte[] encode(Object value) {
        return encodeWith(value, client.getConfig().getCodec().getValueEncoder());
    }

    private byte[] encodeWith(Object value, org.redisson.client.protocol.Encoder encoder) {
        ByteBuf buffer = null;
        try {
            buffer = encoder.encode(value);
            final byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            return bytes;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot encode placement value", error);
        } finally {
            if (buffer != null) buffer.release();
        }
    }

    private Object decode(byte[] value, boolean mapKey) {
        if (value == null) return null;
        final ByteBuf buffer = Unpooled.wrappedBuffer(value);
        try {
            final Codec codec = client.getConfig().getCodec();
            return (mapKey ? codec.getMapKeyDecoder() : codec.getValueDecoder()).decode(buffer, new State());
        } catch (IOException error) {
            throw new IllegalStateException("Cannot decode placement observation", error);
        } finally {
            buffer.release();
        }
    }

    private static Instant time(Object seconds, Object micros) {
        return Instant.ofEpochSecond(Long.parseLong(text(seconds)), Long.parseLong(text(micros)) * 1000);
    }
    private static String text(Object value) { return new String((byte[]) value, StandardCharsets.UTF_8); }
    private static byte[] unbase64(String value) { return java.util.Base64.getDecoder().decode(value); }
    private static Map<String, String> pairs(List<?> list, boolean binary) {
        final Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i += 2) {
            map.put(binary ? java.util.Base64.getEncoder().encodeToString((byte[]) list.get(i)) : text(list.get(i)),
                    binary ? java.util.Base64.getEncoder().encodeToString((byte[]) list.get(i + 1)) : text(list.get(i + 1)));
        }
        return map;
    }
    private static List<List<String>> entries(Map<String, String> map) {
        return map.entrySet().stream().map(entry -> List.of(entry.getKey(), entry.getValue())).toList();
    }
    private static String resource(String name) {
        try (var stream = RedisPlacementStore.class.getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Missing placement script " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
    private record Read(byte[] raw, Object value, long ttl) { }
}
