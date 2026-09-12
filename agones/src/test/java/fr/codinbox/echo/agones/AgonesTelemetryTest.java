package fr.codinbox.echo.agones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class AgonesTelemetryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong nanos = new AtomicLong();
    private AgonesGameServerLifecycle lifecycle;
    private HttpServer server;

    @AfterEach
    void stop() {
        if (lifecycle != null)
            lifecycle.close();
        if (server != null)
            server.stop(0);
    }

    @Test
    void publishesOneAtomicSdkAnnotationWithUnixSeconds() throws Exception {
        final AgonesSdkClient sdk = sdk(200);
        final Instant sampledAt = Instant.parse("2026-09-05T12:00:00Z");

        sdk.telemetry(sampledAt, 4, 3, 100).join();

        assertThat(method.get()).isEqualTo("PUT");
        assertThat(received.get().path("key").asText()).isEqualTo("echo-telemetry");
        assertThat(json.readTree(received.get().path("value").asText()).toString()).isEqualTo(json.createObjectNode()
                .put("version", 1).put("sampledAt", sampledAt.getEpochSecond())
                .put("connectedPlayers", 4).put("publicPlayers", 3).put("publicCapacity", 100).toString());
    }

    @Test
    void sdkFailureIsReportedRatherThanClaimingPublication() throws Exception {
        final AgonesSdkClient sdk = sdk(503);

        assertThatThrownBy(() -> sdk.telemetry(Instant.now(), 0, 0, 475).join())
                .hasRootCauseMessage("Failed to set echo-telemetry annotation: HTTP 503: {}");
    }

    @Test
    void invalidCountsAreNotPublished() throws Exception {
        final AgonesSdkClient sdk = sdk(200);

        assertThatThrownBy(() -> sdk.telemetry(Instant.now(), 1, 2, 100).join())
                .hasRootCauseMessage("Invalid telemetry counts");
        assertThat(received.get()).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -30_000_000_000L, Long.MAX_VALUE - 1})
    void throttlesUntilExactlyFifteenSecondsAndPublishesTheFreshSample(long start) throws Exception {
        nanos.set(start);
        lifecycle(sdk(200));
        final Instant sampledAt = Instant.parse("2026-09-05T12:00:00Z");

        lifecycle.publishTelemetry(sampledAt, 4, 3, 100).join();
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(15) - 1);
        lifecycle.publishTelemetry(sampledAt.plusSeconds(3600), 5, 4, 100).join();
        nanos.incrementAndGet();
        lifecycle.publishTelemetry(sampledAt.plusSeconds(15), 6, 5, 100).join();

        assertThat(requests).hasValue(2);
        assertThat(json.readTree(received.get().path("value").asText()).toString()).isEqualTo(json.createObjectNode()
                .put("version", 1).put("sampledAt", sampledAt.plusSeconds(15).getEpochSecond())
                .put("connectedPlayers", 6).put("publicPlayers", 5).put("publicCapacity", 100).toString());
    }

    @Test
    void failedPublicationIsThrottledAndCanRetryAfterTheInterval() throws Exception {
        lifecycle(sdk(503));
        final Instant sampledAt = Instant.now();

        assertThatThrownBy(() -> lifecycle.publishTelemetry(sampledAt, 0, 0, 100).join())
                .hasRootCauseMessage("Failed to set echo-telemetry annotation: HTTP 503: {}");
        lifecycle.publishTelemetry(sampledAt, 0, 0, 100).join();
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(15));
        assertThatThrownBy(() -> lifecycle.publishTelemetry(sampledAt.plusSeconds(15), 0, 0, 100).join())
                .hasRootCauseMessage("Failed to set echo-telemetry annotation: HTTP 503: {}");

        assertThat(requests).hasValue(2);
    }

    @Test
    void synchronousFailureReleasesTheGuard() throws Exception {
        lifecycle(sdk(200));
        final Instant sampledAt = Instant.now();

        assertThatThrownBy(() -> lifecycle.publishTelemetry(null, 0, 0, 100).join())
                .hasRootCauseInstanceOf(NullPointerException.class);
        lifecycle.publishTelemetry(sampledAt, 0, 0, 100).join();
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(15));
        lifecycle.publishTelemetry(sampledAt.plusSeconds(15), 0, 0, 100).join();

        assertThat(requests).hasValue(1);
    }

    @Test
    void concurrentCallsDoNotOverlapEvenAfterTheIntervalElapses() {
        final HttpClient http = mock(HttpClient.class);
        final CompletableFuture<HttpResponse<String>> pending = new CompletableFuture<>();
        when(http.sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(pending);
        lifecycle(new AgonesSdkClient(http, URI.create("http://127.0.0.1")));

        IntStream.range(0, 32).parallel().forEach(ignored -> lifecycle.publishTelemetry(Instant.now(), 0, 0, 100));
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(30));
        lifecycle.publishTelemetry(Instant.now(), 0, 0, 100).join();

        verify(http, times(1)).sendAsync(any(), any());
        pending.completeExceptionally(new IllegalStateException("SDK unavailable"));
    }

    private void lifecycle(final AgonesSdkClient sdk) {
        lifecycle = new AgonesGameServerLifecycle(sdk, false, "echo.codinbox.fr/draining",
                () -> CompletableFuture.completedFuture(null), Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(30), nanos::get);
    }

    private AgonesSdkClient sdk(final int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/metadata/annotation", exchange -> {
            method.set(exchange.getRequestMethod());
            received.set(json.readTree(exchange.getRequestBody()));
            requests.incrementAndGet();
            exchange.sendResponseHeaders(status, 2);
            exchange.getResponseBody().write(new byte[]{'{', '}'});
            exchange.close();
        });
        server.start();
        return new AgonesSdkClient(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }
}
