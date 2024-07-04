package fr.codinbox.echo.velocity.messaging;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessageHandler;
import fr.codinbox.echo.api.messaging.impl.ServerStatusNotification;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.velocity.utils.ProxyUtils;
import net.kyori.adventure.text.serializer.json.JSONComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class ServerSwitchRequestHandler implements MessageHandler {

    private final @NotNull Logger logger;
    private final @NotNull ProxyServer proxy;

    public ServerSwitchRequestHandler(final @NotNull Logger logger,
                                      final @NotNull ProxyServer proxy) {
        this.logger = logger;
        this.proxy = proxy;
    }

    @Override
    public void onReceive(@NotNull EchoMessage message) {
        if (message instanceof ServerSwitchRequest request) {
            final EchoClient client = Echo.getClient();
            client.getServerById(request.getServerId())
                    .thenCompose(server -> {
                        if (server == null)
                            return null;

                        final RegisteredServer registeredServer = this.proxy.getServer(server.getId()).orElse(null);
                        if (registeredServer == null)
                            return null;

                        final Map<UUID, ServerSwitchRequest.PlayerResponse> connectResults = new HashMap<>();
                        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);

                        for (final UUID userUuid : request.getUserUuids()) {
                            final Player player = this.proxy.getPlayer(userUuid).orElse(null);
                            if (player == null)
                                continue;

                            future = future.thenCompose(aVoid -> player.createConnectionRequest(registeredServer).connect().thenApply(result -> {
                                final ConnectionRequestBuilder.Status status = result.getStatus();
                                final ServerSwitchRequest.PlayerResponse response = new ServerSwitchRequest.PlayerResponse(
                                        result.isSuccessful(),
                                        ServerSwitchRequest.ServerSwitchRequestStatus.valueOf(status.name()),
                                        result.getReasonComponent().isPresent() ? JSONComponentSerializer.json().serialize(result.getReasonComponent().get()) : null
                                );
                                connectResults.put(player.getUniqueId(), response);
                                return null;
                            }));
                        }

                        return future.thenRun(() -> { // All requests has been processed
                            message.reply(new ServerSwitchRequest.Response(connectResults));
                        });
                    });
        }
    }

}
