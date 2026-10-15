package fr.codinbox.echo.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerAvailability;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class EchoMetricsTest {

    @Test
    void exportsJvmResourceAndNetworkMetrics() throws Exception {
        final EchoClient client = mock(EchoClient.class);
        when(client.getCurrentResourceType()).thenReturn(EchoResourceType.PROXY);
        when(client.getCurrentResourceId()).thenReturn(Optional.of("proxy-1"));
        when(client.getAllUsers())
                .thenReturn(EchoFuture.completed(
                        Map.of(UUID.randomUUID(), 0L, UUID.randomUUID(), 0L, UUID.randomUUID(), 0L)));
        when(client.getServers())
                .thenReturn(EchoFuture.completed(Map.of("sg-1", 0L, "sg-2", 0L, "lobby-1", 0L, "gone", 0L)));
        stubServer(client, "sg-1", "survival-games", ServerAvailability.ACTIVE, 2);
        stubServer(client, "sg-2", "survival-games", ServerAvailability.DRAINING, 0);
        stubServer(client, "lobby-1", null, ServerAvailability.ACTIVE, 1);
        when(client.getServerById("gone")).thenReturn(EchoFuture.completed(Optional.empty()));
        when(client.getProxies()).thenReturn(EchoFuture.completed(Map.of("proxy-1", 0L)));
        final Proxy proxy = mock(Proxy.class);
        when(proxy.getConnectedUsers()).thenReturn(EchoFuture.completed(Map.of(UUID.randomUUID(), 0L)));
        when(client.getProxyById("proxy-1")).thenReturn(EchoFuture.completed(Optional.of(proxy)));

        try (EchoMetrics metrics = EchoMetrics.start(
                new EchoMetricsSettings(true, 0, true, Duration.ofMinutes(5), "server_type"),
                client,
                Logger.getLogger("test"))) {
            metrics.refresh();

            final String body = scrape(metrics.port());

            assertThat(body)
                    .contains("jvm_memory_used_bytes")
                    .contains("echo_resource_info{group=\"unknown\",resource_id=\"proxy-1\",resource_type=\"PROXY\"} 1")
                    .contains("echo_network_players 3.0")
                    .contains("echo_network_proxies 1.0")
                    .contains("echo_network_proxy_players{proxy=\"proxy-1\"} 1.0")
                    .contains("echo_network_group_players{group=\"survival-games\"} 2.0")
                    .contains("echo_network_group_players{group=\"unknown\"} 1.0")
                    .contains("echo_network_servers{availability=\"active\",group=\"survival-games\"} 1.0")
                    .contains("echo_network_servers{availability=\"draining\",group=\"survival-games\"} 1.0")
                    .contains("echo_network_server_players{group=\"survival-games\",server=\"sg-1\"} 2.0")
                    .doesNotContain("server=\"gone\"")
                    .doesNotContain("echo_queue_");
        }
    }

    @Test
    void failedCollectionDropsTheNetworkView() throws Exception {
        final EchoClient client = mock(EchoClient.class);
        when(client.getCurrentResourceType()).thenReturn(EchoResourceType.PROXY);
        when(client.getCurrentResourceId()).thenReturn(Optional.of("proxy-1"));
        when(client.getAllUsers())
                .thenReturn(
                        EchoFuture.of(java.util.concurrent.CompletableFuture.failedFuture(new IOException("down"))));
        when(client.getServers()).thenReturn(EchoFuture.completed(Map.of()));
        when(client.getProxies()).thenReturn(EchoFuture.completed(Map.of()));

        try (EchoMetrics metrics = EchoMetrics.start(
                new EchoMetricsSettings(true, 0, true, Duration.ofMinutes(5), "server_type"),
                client,
                Logger.getLogger("test"))) {
            metrics.refresh();

            assertThat(scrape(metrics.port()))
                    .doesNotContain("echo_network_players")
                    .contains("echo_metrics_collection_failures_total{source=\"network\"}");
        }
    }

    private static void stubServer(
            final @NotNull EchoClient client,
            final @NotNull String id,
            final @Nullable String group,
            final @NotNull ServerAvailability availability,
            final int players) {
        final Server server = mock(Server.class);
        doReturn(EchoFuture.completed(Optional.ofNullable(group))).when(server).getProperty("server_type");
        when(server.getAvailability()).thenReturn(EchoFuture.completed(availability));
        final Map<UUID, Long> users = new java.util.HashMap<>();
        for (int i = 0; i < players; i++) users.put(UUID.randomUUID(), 0L);
        when(server.getConnectedUsers()).thenReturn(EchoFuture.completed(users));
        when(client.getServerById(id)).thenReturn(EchoFuture.completed(Optional.of(server)));
    }

    private static @NotNull String scrape(final int port) throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body();
        }
    }
}
