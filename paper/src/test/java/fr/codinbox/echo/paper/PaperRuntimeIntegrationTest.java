package fr.codinbox.echo.paper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** One local login/configuration/disconnect on the released Paper runtime, not a capacity test. */
@Tag("integration")
@Testcontainers
class PaperRuntimeIntegrationTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    @Test
    void paper26PublishesSdkTelemetryAndTracksAnActualPendingConnection() throws Exception {
        try (final Runtime runtime = new Runtime(directory)) {
            final JsonNode telemetry = runtime.telemetry.poll(120, TimeUnit.SECONDS);
            assertThat(telemetry).as("Paper startup log: %s", Files.readString(directory.resolve("server.log"))).isNotNull();
            assertThat(telemetry.toString()).isEqualTo(JSON.createObjectNode().put("version", 1)
                    .put("sampledAt", telemetry.path("sampledAt").asLong())
                    .put("connectedPlayers", 0).put("publicPlayers", 0).put("publicCapacity", 100).toString());
            try (final Socket player = runtime.login()) {
                assertThat(runtime.awaitPending(true).path("joiningMembers").has(Runtime.PLAYER.toString())).isTrue();
            }
            assertThat(runtime.awaitPending(false).path("joiningMembers").has(Runtime.PLAYER.toString())).isFalse();
        }
    }

    private static final class Runtime implements AutoCloseable {
        private static final UUID PLAYER = UUID.nameUUIDFromBytes("OfflinePlayer:EchoSmoke".getBytes(StandardCharsets.UTF_8));
        private final LinkedBlockingQueue<JsonNode> telemetry = new LinkedBlockingQueue<>();
        private final HttpServer sdk;
        private final Process process;
        private final RedissonClient redis;
        private final int port;

        private Runtime(final Path directory) throws Exception {
            Files.createDirectories(directory.resolve("plugins"));
            download("https://fill-data.papermc.io/v1/objects/0de30efb024bc8b83c9c7d507d11802897ad8056b6110ec09fe1a91d126ccb54/paper-26.2-121.jar", directory.resolve("server.jar"));
            download("https://github.com/dandan2611/Connector/releases/download/v8.1.0/connector-paper-8.1.0-all.jar", directory.resolve("plugins/Connector.jar"));
            Files.copy(Path.of(System.getProperty("echo.paper.jar")), directory.resolve("plugins/Echo.jar"));
            Files.writeString(directory.resolve("eula.txt"), "eula=true\n");
            try (final ServerSocket available = new ServerSocket(0)) { port = available.getLocalPort(); }
            Files.writeString(directory.resolve("server.properties"), "server-ip=127.0.0.1\nserver-port=" + port
                    + "\nonline-mode=false\nenforce-secure-profile=false\nnetwork-compression-threshold=-1\n"
                    + "view-distance=2\nsimulation-distance=2\nspawn-protection=0\nlevel-type=minecraft:flat\n");
            final String redisAddress = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
            Files.writeString(directory.resolve("redis.yml"), "codec: !<fr.codinbox.connector.commons.codec.JsonJacksonConnectorCodec> {}\n"
                    + "singleServerConfig:\n  address: \"" + redisAddress + "\"\n");
            final Config config = new Config().setCodec(StringCodec.INSTANCE);
            config.useSingleServer().setAddress(redisAddress);
            redis = Redisson.create(config);
            sdk = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            sdk.createContext("/", exchange -> {
                String response = "{}";
                if (exchange.getRequestURI().getPath().equals("/gameserver"))
                    response = "{\"object_meta\":{\"annotations\":{}},\"status\":{\"state\":\"Allocated\"}}";
                if (exchange.getRequestURI().getPath().equals("/metadata/annotation")) {
                    final JsonNode body = JSON.readTree(exchange.getRequestBody());
                    if (body.path("key").asText().equals("echo-telemetry"))
                        telemetry.add(JSON.readTree(body.path("value").asText()));
                }
                final byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            sdk.start();
            final ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xms256M", "-Xmx1G", "-jar", "server.jar", "--nogui").directory(directory.toFile())
                    .redirectErrorStream(true).redirectOutput(directory.resolve("server.log").toFile());
            builder.environment().put("CONNECTOR_REDIS_ECHO_CONFIG", directory.resolve("redis.yml").toString());
            builder.environment().put("ECHO_RESOURCE_ID", "runtime-smoke");
            builder.environment().put("ECHO_RESOURCE_ADDRESS", "127.0.0.1:" + port);
            builder.environment().put("ECHO_AGONES_ENABLED", "true");
            builder.environment().put("AGONES_SDK_HTTP_PORT", Integer.toString(sdk.getAddress().getPort()));
            process = builder.start();
        }

        private Socket login() throws Exception {
            final int protocol;
            try (final Socket status = new Socket("127.0.0.1", port)) {
                status.setSoTimeout(30000);
                packet(status, handshake(-1, 1));
                packet(status, new byte[]{0});
                final DataInputStream response = new DataInputStream(new ByteArrayInputStream(readPacket(status)));
                varInt(response);
                protocol = JSON.readTree(new String(response.readNBytes(varInt(response)), StandardCharsets.UTF_8))
                        .path("version").path("protocol").asInt();
            }
            final Socket socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(30000);
            packet(socket, handshake(protocol, 2));
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final DataOutputStream login = new DataOutputStream(bytes);
            login.writeByte(0);
            login.writeByte(9);
            login.write("EchoSmoke".getBytes(StandardCharsets.UTF_8));
            login.writeLong(PLAYER.getMostSignificantBits());
            login.writeLong(PLAYER.getLeastSignificantBits());
            packet(socket, bytes.toByteArray());
            final byte[] response = readPacket(socket);
            if (response[0] != 2) {
                socket.close();
                throw new IllegalStateException("Expected login success, got packet " + response[0]);
            }
            // Leave the connection in configuration rather than spawning a gameplay bot.
            packet(socket, new byte[]{3});
            return socket;
        }

        private byte[] handshake(final int protocol, final int state) throws Exception {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final DataOutputStream output = new DataOutputStream(bytes);
            output.writeByte(0);
            writeVarInt(output, protocol);
            output.writeByte(9);
            output.write("localhost".getBytes(StandardCharsets.UTF_8));
            output.writeShort(port);
            output.writeByte(state);
            return bytes.toByteArray();
        }

        private JsonNode awaitPending(final boolean present) throws Exception {
            final Instant deadline = Instant.now().plusSeconds(10);
            while (Instant.now().isBefore(deadline)) {
                final String value = redis.<String>getBucket("server:runtime-smoke:property:admission").get();
                final JsonNode snapshot = JSON.readTree(value);
                if (snapshot.path("joiningMembers").has(PLAYER.toString()) == present)
                    return snapshot;
                Thread.sleep(100);
            }
            throw new IllegalStateException("Pending connection did not become " + present);
        }

        @Override
        public void close() throws Exception {
            try {
                if (process.isAlive()) {
                    process.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
                    process.getOutputStream().flush();
                    if (!process.waitFor(30, TimeUnit.SECONDS))
                        process.destroyForcibly().waitFor();
                }
            } finally {
                sdk.stop(0);
                redis.shutdown();
            }
        }

        private static void download(final String url, final Path target) throws Exception {
            final HttpResponse<Path> response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                    .send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).build(), HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() != 200)
                throw new IllegalStateException("Download failed: " + url + ": " + response.statusCode());
        }

        private static void packet(final Socket socket, final byte[] bytes) throws Exception {
            final DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            writeVarInt(output, bytes.length);
            output.write(bytes);
            output.flush();
        }

        private static byte[] readPacket(final Socket socket) throws Exception {
            return socket.getInputStream().readNBytes(varInt(socket.getInputStream()));
        }

        private static int varInt(final InputStream input) throws Exception {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                final int next = input.read();
                if (next < 0) throw new IllegalStateException("Connection closed");
                value |= (next & 127) << shift;
                if ((next & 128) == 0) return value;
            }
            throw new IllegalStateException("Invalid VarInt");
        }

        private static void writeVarInt(final DataOutputStream output, int value) throws Exception {
            while ((value & ~127) != 0) {
                output.writeByte((value & 127) | 128);
                value >>>= 7;
            }
            output.writeByte(value);
        }
    }
}
