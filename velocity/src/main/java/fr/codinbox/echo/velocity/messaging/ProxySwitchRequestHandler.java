package fr.codinbox.echo.velocity.messaging;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessageHandler;
import fr.codinbox.echo.api.messaging.impl.ProxySwitchRequest;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.core.proxy.ProxyImpl;
import net.kyori.adventure.text.serializer.json.JSONComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class ProxySwitchRequestHandler implements MessageHandler {

    private final @NotNull Logger logger;
    private final @NotNull ProxyServer proxy;

    public ProxySwitchRequestHandler(final @NotNull Logger logger,
                                     final @NotNull ProxyServer proxy) {
        this.logger = logger;
        this.proxy = proxy;
    }

    @Override
    public void onReceive(@NotNull EchoMessage message) {
        if (message instanceof ProxySwitchRequest request) {
            final EchoClient client = Echo.getClient();
            final String proxyId = request.getProxyId();

            client.getProxyById(proxyId).thenAccept(p -> {
                if (p == null)
                    return;

                final Address address = p.getAddress();

                for (UUID userUuid : request.getUserUuids()) {
                    final Player player = this.proxy.getPlayer(userUuid).orElse(null);
                    if (player == null)
                        continue;

                    player.transferToHost(address.toInetSocketAddress());

                    this.logger.info("Transferred player " + player.getUsername() + " to " + address.getHost() + ":" + address.getPort() + " (proxy '" + proxyId + "').");
                }
            });
        }
    }

}
