package fr.codinbox.echo.velocity.messaging;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.messaging.impl.ServerAvailabilityNotification;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerAvailability;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class ServerAvailabilityNotificationHandlerTest {

    @Test
    void activeServerIsRegistered() {
        ProxyServer proxy = mock(ProxyServer.class);
        EchoClient client = mock(EchoClient.class);
        Server server = mock(Server.class);
        ServerAvailabilityNotification notification = mock(ServerAvailabilityNotification.class);
        when(notification.getAvailability()).thenReturn(ServerAvailability.ACTIVE);
        when(notification.getId()).thenReturn("lobby-1");
        when(notification.getAddress()).thenReturn(new Address("127.0.0.1", 25565));
        when(client.getServerById("lobby-1")).thenReturn(EchoFuture.completed(Optional.of(server)));
        when(server.getId()).thenReturn("lobby-1");
        when(server.getAddress()).thenReturn(new Address("127.0.0.1", 25565));
        when(server.getAvailability()).thenReturn(EchoFuture.completed(ServerAvailability.ACTIVE));

        new ServerAvailabilityNotificationHandler(mock(Logger.class), proxy, client).onReceive(notification);

        verify(proxy).registerServer(any(ServerInfo.class));
    }

    @Test
    void drainingServerIsUnregisteredWithoutLeavingEcho() {
        ProxyServer proxy = mock(ProxyServer.class);
        EchoClient client = mock(EchoClient.class);
        RegisteredServer registered = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        ServerAvailabilityNotification notification = mock(ServerAvailabilityNotification.class);
        when(notification.getAvailability()).thenReturn(ServerAvailability.DRAINING);
        when(notification.getId()).thenReturn("lobby-1");
        when(proxy.getServer("lobby-1")).thenReturn(Optional.of(registered));
        when(registered.getServerInfo()).thenReturn(info);

        new ServerAvailabilityNotificationHandler(mock(Logger.class), proxy, client).onReceive(notification);

        verify(proxy).unregisterServer(info);
    }
}
