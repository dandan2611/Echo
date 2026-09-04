package fr.codinbox.echo.velocity.messaging;

import com.velocitypowered.api.proxy.ProxyServer;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.messaging.MessageHandler;
import fr.codinbox.echo.api.messaging.impl.ServerAvailabilityNotification;
import fr.codinbox.echo.api.server.ServerAvailability;
import fr.codinbox.echo.velocity.utils.ProxyUtils;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

public final class ServerAvailabilityNotificationHandler implements MessageHandler<ServerAvailabilityNotification> {

    private final Logger logger;
    private final ProxyServer proxy;
    private final EchoClient client;

    public ServerAvailabilityNotificationHandler(final @NotNull Logger logger,
                                                  final @NotNull ProxyServer proxy,
                                                  final @NotNull EchoClient client) {
        this.logger = logger;
        this.proxy = proxy;
        this.client = client;
    }

    @Override
    public void onReceive(final @NotNull ServerAvailabilityNotification notification) {
        if (notification.getAvailability() == ServerAvailability.ACTIVE) {
            this.client.getServerById(notification.getId()).thenAccept(server ->
                    server.ifPresent(value -> ProxyUtils.registerServerIfActive(this.proxy, this.logger, value)));
        } else {
            ProxyUtils.unregisterServer(this.proxy, this.logger, notification.getId());
        }
    }
}
