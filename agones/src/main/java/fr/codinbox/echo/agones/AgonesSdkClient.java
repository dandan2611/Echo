package fr.codinbox.echo.agones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

final class AgonesSdkClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI baseUri;

    AgonesSdkClient(final HttpClient http, final URI baseUri) {
        this.http = http;
        this.baseUri = baseUri;
    }

    CompletableFuture<Void> ready() {
        return post("/ready");
    }

    CompletableFuture<Void> health() {
        return post("/health");
    }

    CompletableFuture<Void> allocate() {
        return post("/allocate");
    }

    CompletableFuture<Void> shutdown() {
        return post("/shutdown");
    }

    CompletableFuture<Void> telemetry(final Instant sampledAt, final int connectedPlayers,
                                      final int publicPlayers, final int publicCapacity) {
        if (connectedPlayers < 0 || publicPlayers < 0 || publicPlayers > connectedPlayers || publicCapacity <= 0)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid telemetry counts"));
        final String value = JSON.createObjectNode().put("version", 1)
                .put("sampledAt", sampledAt.getEpochSecond()).put("connectedPlayers", connectedPlayers)
                .put("publicPlayers", publicPlayers).put("publicCapacity", publicCapacity).toString();
        final String body = JSON.createObjectNode().put("key", "echo-telemetry").put("value", value).toString();
        final HttpRequest request = HttpRequest.newBuilder(this.baseUri.resolve("/metadata/annotation"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        return this.http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            requireSuccess(response, "set echo-telemetry annotation");
            return null;
        });
    }

    CompletableFuture<Boolean> isDrainRequested(final String annotation) {
        return gameServer().thenApply(gameServer -> {
            final JsonNode value = gameServer.path("object_meta").path("annotations").path(annotation);
            return value.isBoolean() ? value.asBoolean() : Boolean.parseBoolean(value.asText());
        });
    }

    CompletableFuture<Boolean> isInState(final String... states) {
        return gameServer().thenApply(gameServer -> Arrays.asList(states)
                .contains(gameServer.path("status").path("state").asText()));
    }

    private CompletableFuture<JsonNode> gameServer() {
        final HttpRequest request = HttpRequest.newBuilder(this.baseUri.resolve("/gameserver"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return this.http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    requireSuccess(response, "read GameServer");
                    try {
                        return JSON.readTree(response.body());
                    } catch (Exception exception) {
                        throw new IllegalStateException("Invalid Agones GameServer response", exception);
                    }
                });
    }

    private CompletableFuture<Void> post(final String path) {
        final HttpRequest request = HttpRequest.newBuilder(this.baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return this.http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    requireSuccess(response, "call " + path);
                    return null;
                });
    }

    private static void requireSuccess(final HttpResponse<String> response, final String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("Failed to " + operation + ": HTTP "
                    + response.statusCode() + ": " + response.body());
    }
}
