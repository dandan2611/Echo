package fr.codinbox.echo.core.server.placement;

import fr.codinbox.connector.commons.codec.JsonJacksonConnectorCodec;
import fr.codinbox.echo.api.server.ServerAdmissionSnapshot;
import fr.codinbox.echo.api.server.ServerLoad;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Executable acceptance tests on disposable loopback Redis and the real Connector codec. */
public final class PlacementFaultLab {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static RedissonClient client(int port) {
        final Config config = new Config();
        config.setCodec(new JsonJacksonConnectorCodec());
        config.useSingleServer().setAddress("redis://127.0.0.1:" + port)
                .setDatabase(15)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(2);
        return Redisson.create(config);
    }

    public static void main(String[] args) throws Exception {
        final RedissonClient direct = client(16379);
        final RedissonClient faulty = client(16380);
        try {
            final var observer = new RedisServerPlacement(direct, Clock.systemUTC());
            final var writer = new RedisServerPlacement(faulty, Clock.systemUTC());
            for (String mode : new String[]{"commit-lost", "commit-reply-lost", "commit-replay"}) {
                for (String operation : new String[]{"publish", "reserve", "renew", "release"}) {
                    final String server = "lab-" + UUID.randomUUID();
                    seed(direct, server);
                    writer.startAdmissionPublisher(server);
                    writer.publishAdmission(server, snapshot());
                    final var request = new ServerPlacement.Request(server, Set.of(UUID.randomUUID()), Set.of(server),
                            Map.of(), ServerPlacement.Policy.FILL_MOST_LOADED, Duration.ofSeconds(30));
                    final var lease = operation.equals("renew") || operation.equals("release")
                            ? observer.reserve(request).join().orElseThrow() : null;
                    control("arm/" + mode);
                    final var published = snapshot();
                    boolean confirmed = false;
                    Object result = null;
                    try {
                        result = switch (operation) {
                            case "publish" -> { writer.publishAdmission(server, published); yield null; }
                            case "reserve" -> writer.reserve(request).join();
                            case "renew" -> writer.renew(lease, Duration.ofSeconds(45)).join();
                            case "release" -> writer.release(lease).join();
                            default -> throw new AssertionError(operation);
                        };
                        confirmed = true;
                    } catch (RuntimeException uncertain) {
                        final Throwable cause = uncertain instanceof java.util.concurrent.CompletionException
                                ? uncertain.getCause() : uncertain;
                        if (!(cause instanceof PlacementUnavailableException)
                                && !(cause instanceof org.redisson.client.RedisTimeoutException)
                                && !(cause instanceof org.redisson.client.RedisConnectionException)) throw uncertain;
                        // A lost response may report unavailability, but cannot strand global placement.
                        System.out.println("UNCERTAIN " + mode + "/" + operation + ": " + uncertain.getClass().getSimpleName());
                    }
                    final String evidence = control("events");
                    require(evidence.contains("[\"" + mode + "\"]"), "Injection must execute: " + evidence);
                    // Observe the original effect before any healthy publication or repairing retry.
                    final var observed = observer.findActiveReservation(request.requestId()).join();
                    if (confirmed) {
                        switch (operation) {
                            case "publish" -> require(direct.getBucket("server:" + server + ":property:admission")
                                    .get().equals(published), "Confirmed publication must be visible");
                            case "reserve" -> {
                                require(result instanceof java.util.Optional<?> value && value.isPresent(), "Reserve must return a lease");
                                require(observed.isPresent(), "Confirmed reserve must be visible");
                            }
                            case "renew" -> {
                                require(result instanceof java.util.Optional<?> value && value.isPresent(), "Renew must return a lease");
                                require(observed.orElseThrow().expiresAt().isAfter(lease.expiresAt()), "Renew must extend the original lease");
                            }
                            case "release" -> {
                                require(Boolean.TRUE.equals(result), "Release must confirm removal");
                                require(observed.isEmpty(), "Confirmed release must already be absent");
                            }
                        }
                    } else if (operation.equals("renew")) {
                        require(observed.isPresent(), "Uncertain renew cannot delete a live lease");
                        require(!observed.orElseThrow().expiresAt().isBefore(lease.expiresAt()), "Uncertain renew cannot shorten a lease");
                    }
                    final long recovery = System.nanoTime();
                    writer.publishAdmission(server, snapshot()); // same persistent writer remains healthy
                    if (operation.equals("publish")) {
                        require(writer.admit(server, UUID.randomUUID(), false, snapshot()), "Empty destination must admit");
                    } else {
                        if (lease != null && operation.equals("release")) observer.release(lease).join();
                        final var current = observer.reserve(request).join().orElseThrow();
                        require(observer.reserve(request).join().orElseThrow().equals(current), "Request replay must keep its lease");
                        final var contender = new ServerPlacement.Request(server + "-other", Set.of(UUID.randomUUID()),
                                Set.of(server), Map.of(), ServerPlacement.Policy.FILL_MOST_LOADED, Duration.ofSeconds(30));
                        require(observer.reserve(contender).join().isEmpty(), "A replay must not oversell the last seat");
                        require(observer.release(current).join(), "Lease must remain releasable");
                    }
                    require(System.nanoTime() - recovery < Duration.ofSeconds(5).toNanos(), "Recovery exceeds five seconds");
                    require(!direct.getBucket("placement:lock").isExists(), "No lock may be created");
                    direct.getMap("servers:map").remove(server);
                    System.out.println("PASS " + mode + "/" + operation + " recoveryMs=" +
                            Duration.ofNanos(System.nanoTime() - recovery).toMillis());
                }
            }
        } finally {
            faulty.shutdown();
            direct.shutdown();
        }
    }

    private static void seed(RedissonClient client, String id) {
        client.<String, Long>getMap("servers:map").put(id, Instant.now().toEpochMilli());
        client.getBucket("heartbeat:server:" + id).set(1, Duration.ofMinutes(5));
        client.getBucket("server:" + id + ":property:placement_capacity").set(1);
        client.getBucket("server:" + id + ":property:placement_hard_capacity").set(1);
        client.getBucket("server:" + id + ":property:load").set(new ServerLoadSnapshot(
                new ServerLoad(0, true), Instant.now(), Instant.now().plusSeconds(120)));
    }

    private static ServerAdmissionSnapshot snapshot() {
        final Instant now = Instant.now();
        return new ServerAdmissionSnapshot(Map.of(), Map.of(), 1, 1, now, now.plusSeconds(5));
    }
    private static String control(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:16381/" + path)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
