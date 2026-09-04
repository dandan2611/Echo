package fr.codinbox.echo.velocity.messaging;

import com.velocitypowered.api.proxy.ProxyServer;
import fr.codinbox.echo.api.messaging.MessageHandler;
import fr.codinbox.echo.api.messaging.impl.ServerStatusNotification;
import fr.codinbox.echo.velocity.utils.ProxyUtils;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

public class ServerStatusNotificationHandler implements MessageHandler<ServerStatusNotification> {

    private final @NotNull Logger logger;
    private final @NotNull ProxyServer proxy;

    public ServerStatusNotificationHandler(final @NotNull Logger logger,
                                           final @NotNull ProxyServer proxy) {
        this.logger = logger;
        this.proxy = proxy;
    }

    @Override
    public void onReceive(@NotNull ServerStatusNotification notification) {
        switch (notification.getStatus()) {
            case REGISTERED -> ProxyUtils.registerServer(this.proxy, this.logger, notification.getId(), notification.getAddress());
            case UNREGISTERED -> ProxyUtils.unregisterServer(
                    this.proxy, this.logger, notification.getId());
        }
    }

}
