package fr.codinbox.echo.agones;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class AgonesGameServerLifecycleTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null)
            server.stop(0);
    }

    @Test
    void longLivedServer_becomesReadyAllocatedHealthyAndDrains() throws Exception {
        AtomicInteger ready = new AtomicInteger();
        AtomicInteger allocated = new AtomicInteger();
        AtomicInteger health = new AtomicInteger();
        AtomicInteger shutdown = new AtomicInteger();
        AtomicBoolean drained = new AtomicBoolean();
        AtomicInteger drainAttempts = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ready", exchange -> respond(exchange, ready));
        server.createContext("/allocate", exchange -> respond(exchange, allocated));
        server.createContext("/health", exchange -> respond(exchange, health));
        server.createContext("/shutdown", exchange -> respond(exchange, shutdown));
        server.createContext("/gameserver", exchange -> {
            byte[] body = ("{\"object_meta\":{\"annotations\":{\"echo.codinbox.fr/draining\":\"true\"}},"
                    + "\"status\":{\"state\":\"" + (allocated.get() > 0 ? "Allocated" : "Ready") + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        AgonesSdkClient sdk = new AgonesSdkClient(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        AgonesGameServerLifecycle lifecycle = new AgonesGameServerLifecycle(
                sdk, true, "echo.codinbox.fr/draining", () -> {
                    if (drainAttempts.incrementAndGet() == 1)
                        return java.util.concurrent.CompletableFuture.failedFuture(
                                new IllegalStateException("Redis unavailable"));
                    drained.set(true);
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                },
                Duration.ofMillis(5), Duration.ofMillis(5), Duration.ofSeconds(1));

        lifecycle.start().join();
        assertThat(drained).isFalse();
        assertThat(lifecycle.watchForDrainRequests().join()).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while ((!drained.get() || health.get() == 0) && System.nanoTime() < deadline)
            Thread.sleep(5);

        assertThat(ready).hasValue(1);
        assertThat(allocated).hasValue(1);
        assertThat(health.get()).isPositive();
        assertThat(drained).isTrue();
        assertThat(drainAttempts).hasValue(2);
        lifecycle.close();
        assertThat(shutdown).hasValue(1);
    }

    @Test
    void longLivedServer_waitsForAllocatedState() throws Exception {
        AtomicBoolean allocated = new AtomicBoolean();
        CountDownLatch allocateCalled = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ready", exchange -> respond(exchange, new AtomicInteger()));
        server.createContext("/allocate", exchange -> {
            allocateCalled.countDown();
            respond(exchange, new AtomicInteger());
        });
        server.createContext("/health", exchange -> respond(exchange, new AtomicInteger()));
        server.createContext("/shutdown", exchange -> respond(exchange, new AtomicInteger()));
        server.createContext("/gameserver", exchange -> {
            byte[] body = ("{\"object_meta\":{\"annotations\":{}},\"status\":{\"state\":\""
                    + (allocated.get() ? "Allocated" : "Ready") + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        AgonesGameServerLifecycle lifecycle = new AgonesGameServerLifecycle(
                new AgonesSdkClient(HttpClient.newHttpClient(),
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort())),
                true, "echo.codinbox.fr/draining",
                () -> java.util.concurrent.CompletableFuture.completedFuture(null),
                Duration.ofMillis(5), Duration.ofMillis(5), Duration.ofSeconds(1));

        var startup = lifecycle.start();
        assertThat(allocateCalled.await(1, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> startup.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        allocated.set(true);
        startup.join();
        lifecycle.close();
    }

    @Test
    void shortLivedServer_acceptsAllocationBeforeReadyStateIsObserved() throws Exception {
        AtomicInteger ready = new AtomicInteger();
        AtomicInteger allocate = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ready", exchange -> respond(exchange, ready));
        server.createContext("/allocate", exchange -> respond(exchange, allocate));
        server.createContext("/health", exchange -> respond(exchange, new AtomicInteger()));
        server.createContext("/shutdown", exchange -> respond(exchange, new AtomicInteger()));
        server.createContext("/gameserver", exchange -> {
            byte[] body = "{\"object_meta\":{\"annotations\":{}},\"status\":{\"state\":\"Allocated\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        AgonesGameServerLifecycle lifecycle = new AgonesGameServerLifecycle(
                new AgonesSdkClient(HttpClient.newHttpClient(),
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort())),
                false, "echo.codinbox.fr/draining",
                () -> java.util.concurrent.CompletableFuture.completedFuture(null),
                Duration.ofMillis(5), Duration.ofMillis(5), Duration.ofSeconds(1));

        lifecycle.start().join();
        lifecycle.close();

        assertThat(ready).hasValue(0);
        assertThat(allocate).hasValue(0);
    }

    @Test
    void shortLivedServer_waitsForExternalAllocationAfterBecomingReady() throws Exception {
        AtomicInteger ready = new AtomicInteger();
        AtomicInteger allocate = new AtomicInteger();
        AtomicBoolean allocated = new AtomicBoolean();
        CountDownLatch readyCalled = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ready", exchange -> {
            readyCalled.countDown();
            respond(exchange, ready);
        });
        server.createContext("/allocate", exchange -> respond(exchange, allocate));
        server.createContext("/health", exchange -> respond(exchange, new AtomicInteger()));
        server.createContext("/shutdown", exchange -> respond(exchange, new AtomicInteger()));
        server.createContext("/gameserver", exchange -> {
            byte[] body = ("{\"object_meta\":{\"annotations\":{}},\"status\":{\"state\":\""
                    + (allocated.get() ? "Allocated" : "Ready") + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        AgonesGameServerLifecycle lifecycle = new AgonesGameServerLifecycle(
                new AgonesSdkClient(HttpClient.newHttpClient(),
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort())),
                false, "echo.codinbox.fr/draining",
                () -> java.util.concurrent.CompletableFuture.completedFuture(null),
                Duration.ofMillis(5), Duration.ofMillis(5), Duration.ofSeconds(1));

        var startup = lifecycle.start();
        assertThat(readyCalled.await(1, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> startup.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        allocated.set(true);
        startup.join();
        lifecycle.close();

        assertThat(ready).hasValue(1);
        assertThat(allocate).hasValue(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, AtomicInteger calls)
            throws java.io.IOException {
        calls.incrementAndGet();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
