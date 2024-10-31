package fr.codinbox.echo.velocity.messaging;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessageHandler;
import fr.codinbox.echo.api.messaging.impl.ProxySwitchRequest;
import fr.codinbox.echo.api.server.Address;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
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

            client.getProxyByIdAsync(proxyId).thenAccept(proxyOpt -> {
                proxyOpt.ifPresent(proxy -> {
                    final Address address = proxy.getAddress();

                    for (UUID userUuid : request.getUserUuids()) {
                        final Player player = this.proxy.getPlayer(userUuid).orElse(null);
                        if (player == null)
                            continue;

                        player.transferToHost(address.toInetSocketAddress());

                        this.logger.info("Transferred player " + player.getUsername() + " to " + address.getHost() + ":" + address.getPort() + " (proxy '" + proxyId + "').");
                    }
                });
            });
        }
    }

}
