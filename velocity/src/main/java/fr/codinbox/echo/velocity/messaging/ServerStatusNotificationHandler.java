package fr.codinbox.echo.velocity.messaging;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessageHandler;
import fr.codinbox.echo.api.messaging.impl.ServerStatusNotification;
import fr.codinbox.echo.velocity.utils.ProxyUtils;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

public class ServerStatusNotificationHandler implements MessageHandler {

    private final @NotNull Logger logger;
    private final @NotNull ProxyServer proxy;

    public ServerStatusNotificationHandler(final @NotNull Logger logger,
                                           final @NotNull ProxyServer proxy) {
        this.logger = logger;
        this.proxy = proxy;
    }

    @Override
    public void onReceive(@NotNull EchoMessage message) {
        if (message instanceof ServerStatusNotification notification) {
            if (ServerStatusNotification.Status.REGISTERED.equals(notification.getStatus())) {
                ProxyUtils.registerServer(this.proxy, this.logger, notification.getId(), notification.getAddress());
            } else if (ServerStatusNotification.Status.UNREGISTERED.equals(notification.getStatus())) {
                final RegisteredServer registeredServer = this.proxy.getServer(notification.getId()).orElse(null);
                if (registeredServer == null)
                    return;
                final ServerInfo info = registeredServer.getServerInfo();
                this.proxy.unregisterServer(info);
                this.logger.info("Unregistered server '" + notification.getId() + "'");
            }
        }
    }

}
