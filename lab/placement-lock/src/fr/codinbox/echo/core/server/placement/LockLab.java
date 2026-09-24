package fr.codinbox.echo.core.server.placement;

import fr.codinbox.echo.api.server.ServerAdmissionSnapshot;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class LockLab {
    private static boolean defaults;

    private static RedissonClient client(int port) {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:" + port)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(2);
        if (!defaults) {
            config.setLockWatchdogTimeout(3000);
            config.useSingleServer().setTimeout(400).setRetryAttempts(0).setRetryInterval(100);
        }
        return Redisson.create(config);
    }

    private static ServerAdmissionSnapshot snapshot() {
        Instant now = Instant.now();
        return new ServerAdmissionSnapshot(Map.of(), Map.of(), 100, 120, now, now.plusSeconds(5));
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        boolean pause = Boolean.parseBoolean(args[1]);
        defaults = args[2].equals("default");
        RedissonClient observer = client(16379);
        RedissonClient sg = client(16380);
        try {
            // Dedicated disposable Redis only. Remove named lab keys, never FLUSHALL.
            observer.getKeys().delete("placement:lock", "server:lab-sg:property:admission", "server:lab-lobby:property:admission");
            RedisServerPlacement publisher = new RedisServerPlacement(sg, Clock.systemUTC());
            RedisServerPlacement lobby = new RedisServerPlacement(observer, Clock.systemUTC());
            publisher.publishAdmission("lab-sg", snapshot());
            if (!lobby.admit("lab-lobby", UUID.randomUUID(), false, snapshot()))
                throw new AssertionError("Baseline empty lobby must admit");
            System.out.println("BASELINE empty lobby admits=true");
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:16381/arm/" + mode)).build(), HttpResponse.BodyHandlers.ofString());
            try {
                publisher.publishAdmission("lab-sg", snapshot());
                System.out.println("FAULT publication returned normally");
            } catch (RuntimeException error) {
                System.out.println("FAULT publication exception=" + error.getClass().getSimpleName());
                if (error.getCause() != null) System.out.println("FAULT cause=" + error.getCause().getClass().getSimpleName());
            }
            System.out.println("AFTER FAULT holds=" + observer.getMap("placement:lock", StringCodec.INSTANCE).readAllMap());
            String events = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:16381/events")).build(), HttpResponse.BodyHandlers.ofString()).body();
            if (!mode.equals("baseline") && !events.contains("[\"" + mode + "\"]")) throw new AssertionError("Fault not injected: " + events);
            if (pause) {
                Thread.sleep(defaults ? 34000 : 4000);
                System.out.println("IDLE AFTER TTL holds=" + observer.getMap("placement:lock", StringCodec.INSTANCE).readAllMap());
            }
            boolean allAdmitted = true;
            boolean lastAdmitted = false;
            for (int i = 0; i < (defaults ? 34 : 12); i++) {
                Thread.sleep(defaults ? 1000 : 400);
                publisher.publishAdmission("lab-sg", snapshot()); // same ordered writer/thread as Paper
                boolean admitted = lobby.admit("lab-lobby", UUID.randomUUID(), false, snapshot());
                allAdmitted &= admitted;
                lastAdmitted = admitted;
                System.out.println("TICK " + i + " holds=" + observer.getMap("placement:lock", StringCodec.INSTANCE).readAllMap()
                        + " ttl=" + observer.getLock("placement:lock").remainTimeToLive() + " emptyLobbyAdmits=" + admitted);
            }
            System.out.println("INJECTOR " + events);
            if (!lastAdmitted) throw new AssertionError("BUG: empty lobby remains rejected after transport recovered, beyond watchdog TTL");
            if ((mode.equals("baseline") || pause) && !allAdmitted)
                throw new AssertionError("Control case denied admission");
            System.out.println("PASS no leaked lock and empty lobby admits");
        } finally {
            sg.shutdown();
            observer.shutdown();
        }
    }
}
