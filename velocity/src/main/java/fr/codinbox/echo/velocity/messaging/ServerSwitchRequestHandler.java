package fr.codinbox.echo.velocity.messaging;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.messaging.MessageHandler;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerAvailability;
import net.kyori.adventure.text.serializer.json.JSONComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class ServerSwitchRequestHandler implements MessageHandler<ServerSwitchRequest> {

    private final @NotNull Logger logger;
    private final @NotNull ProxyServer proxy;

    public ServerSwitchRequestHandler(final @NotNull Logger logger,
                                      final @NotNull ProxyServer proxy) {
        this.logger = logger;
        this.proxy = proxy;
    }

    @Override
    public void onReceive(@NotNull ServerSwitchRequest request) {
        if (this.expired(request)) {
            this.replyAll(request, ServerSwitchRequest.ServerSwitchRequestStatus.TIMED_OUT);
            return;
        }
        final EchoClient client = Echo.getClient();
        client.getServerById(request.getServerId())
                .thenCompose(serverOpt -> {
                    if (serverOpt.isEmpty()) {
                        this.replyAll(request, ServerSwitchRequest.ServerSwitchRequestStatus.TARGET_SERVER_NOT_FOUND);
                        return CompletableFuture.completedFuture(null);
                    }

                    final Server server = serverOpt.get();

                    return server.getAvailability().thenCompose(availability -> {
                        if (availability != ServerAvailability.ACTIVE) {
                            this.replyAll(request, ServerSwitchRequest.ServerSwitchRequestStatus.TARGET_SERVER_UNAVAILABLE);
                            return CompletableFuture.completedFuture(null);
                        }

                        final RegisteredServer registeredServer = this.proxy.getServer(server.getId()).orElse(null);
                        if (registeredServer == null) {
                            this.replyAll(request, ServerSwitchRequest.ServerSwitchRequestStatus.TARGET_SERVER_NOT_REGISTERED);
                            return CompletableFuture.completedFuture(null);
                        }

                        final Map<UUID, ServerSwitchRequest.PlayerResponse> connectResults = new ConcurrentHashMap<>();

                        return CompletableFuture.allOf(
                                Arrays.stream(request.getUserUuids())
                                        .map(userUuid -> {
                                            if (this.expired(request)) {
                                                connectResults.put(userUuid, response(
                                                        ServerSwitchRequest.ServerSwitchRequestStatus.TIMED_OUT, null));
                                                return CompletableFuture.completedFuture(null);
                                            }
                                            final Player player = this.proxy.getPlayer(userUuid).orElse(null);
                                            if (player == null) {
                                                connectResults.put(userUuid, response(
                                                        ServerSwitchRequest.ServerSwitchRequestStatus.PLAYER_NOT_CONNECTED, null));
                                                return CompletableFuture.completedFuture(null);
                                            }

                                            try {
                                                CompletableFuture<ConnectionRequestBuilder.Result> connection =
                                                        player.createConnectionRequest(registeredServer).connect();
                                                final long deadline = request.getTransferDeadlineEpochMillis();
                                                if (deadline > 0) {
                                                    connection = connection.copy().orTimeout(
                                                            Math.max(0, deadline - System.currentTimeMillis()),
                                                            MILLISECONDS);
                                                }
                                                return connection
                                                        .handle((result, error) -> {
                                                            if (error != null) {
                                                                if (error instanceof TimeoutException) {
                                                                    connectResults.put(userUuid, response(
                                                                            ServerSwitchRequest.ServerSwitchRequestStatus.TIMED_OUT,
                                                                            null));
                                                                } else {
                                                                    this.connectionFailed(connectResults, userUuid, server, error);
                                                                }
                                                                return null;
                                                            }
                                                            final String reason = result.getReasonComponent().isPresent()
                                                                    ? JSONComponentSerializer.json().serialize(
                                                                            result.getReasonComponent().get())
                                                                    : null;
                                                            connectResults.put(userUuid,
                                                                    response(map(result.getStatus()), reason));
                                                            return null;
                                                        });
                                            } catch (RuntimeException error) {
                                                this.connectionFailed(connectResults, userUuid, server, error);
                                                return CompletableFuture.completedFuture(null);
                                            }
                                        })
                                        .toArray(CompletableFuture[]::new)
                        ).thenRun(() -> request.reply(new ServerSwitchRequest.Response(connectResults)));
                    });
                }).exceptionally(e -> {
                    this.logger.log(Level.SEVERE,
                            "Error while resolving server " + request.getServerId(), e);
                    this.replyAll(request, ServerSwitchRequest.ServerSwitchRequestStatus.INTERNAL_ERROR);
                    return null;
                });
    }

    private void replyAll(ServerSwitchRequest request, ServerSwitchRequest.ServerSwitchRequestStatus status) {
        final Map<UUID, ServerSwitchRequest.PlayerResponse> responses = new LinkedHashMap<>();
        Arrays.stream(request.getUserUuids()).forEach(uuid -> responses.put(uuid, response(status, null)));
        request.reply(new ServerSwitchRequest.Response(responses));
    }

    private boolean expired(ServerSwitchRequest request) {
        return request.getTransferDeadlineEpochMillis() > 0
                && System.currentTimeMillis() >= request.getTransferDeadlineEpochMillis();
    }

    private void connectionFailed(Map<UUID, ServerSwitchRequest.PlayerResponse> results, UUID userId,
                                  Server server, Throwable error) {
        this.logger.log(Level.SEVERE, "Error while switching player " + userId + " to " + server.getId(), error);
        results.put(userId, response(ServerSwitchRequest.ServerSwitchRequestStatus.INTERNAL_ERROR, null));
    }

    private static ServerSwitchRequest.PlayerResponse response(
            ServerSwitchRequest.ServerSwitchRequestStatus status, String reason) {
        final boolean successful = status == ServerSwitchRequest.ServerSwitchRequestStatus.SUCCESS
                || status == ServerSwitchRequest.ServerSwitchRequestStatus.ALREADY_CONNECTED;
        return new ServerSwitchRequest.PlayerResponse(successful, status, reason);
    }

    private static ServerSwitchRequest.ServerSwitchRequestStatus map(ConnectionRequestBuilder.Status status) {
        return switch (status) {
            case SUCCESS -> ServerSwitchRequest.ServerSwitchRequestStatus.SUCCESS;
            case ALREADY_CONNECTED -> ServerSwitchRequest.ServerSwitchRequestStatus.ALREADY_CONNECTED;
            case CONNECTION_IN_PROGRESS -> ServerSwitchRequest.ServerSwitchRequestStatus.CONNECTION_IN_PROGRESS;
            case CONNECTION_CANCELLED -> ServerSwitchRequest.ServerSwitchRequestStatus.CONNECTION_CANCELLED;
            case SERVER_DISCONNECTED -> ServerSwitchRequest.ServerSwitchRequestStatus.SERVER_DISCONNECTED;
        };
    }

}
