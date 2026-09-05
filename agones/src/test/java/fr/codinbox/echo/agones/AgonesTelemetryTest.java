package fr.codinbox.echo.agones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class AgonesTelemetryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private HttpServer server;

    @AfterEach
    void stop() {
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

    private AgonesSdkClient sdk(final int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/metadata/annotation", exchange -> {
            method.set(exchange.getRequestMethod());
            received.set(json.readTree(exchange.getRequestBody()));
            exchange.sendResponseHeaders(status, 2);
            exchange.getResponseBody().write(new byte[]{'{', '}'});
            exchange.close();
        });
        server.start();
        return new AgonesSdkClient(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }
}
