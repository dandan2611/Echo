package fr.codinbox.echo.velocity.messaging;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import fr.codinbox.echo.api.messaging.impl.ServerStatusNotification;
import fr.codinbox.echo.api.server.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ServerStatusNotificationHandlerTest {

    private ServerStatusNotificationHandler handler;

    @Mock
    private ProxyServer mockProxy;
    @Mock
    private Logger mockLogger;

    @BeforeEach
    void setUp() {
        handler = new ServerStatusNotificationHandler(mockLogger, mockProxy);
    }

    @Test
    void onReceive_registered_registersServer() {
        ServerStatusNotification notification = mock(ServerStatusNotification.class);
        when(notification.getStatus()).thenReturn(ServerStatusNotification.Status.REGISTERED);
        when(notification.getId()).thenReturn("lobby-1");
        when(notification.getAddress()).thenReturn(new Address("127.0.0.1", 25565));

        handler.onReceive(notification);

        ArgumentCaptor<ServerInfo> captor = ArgumentCaptor.forClass(ServerInfo.class);
        verify(mockProxy).registerServer(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("lobby-1");
    }

    @Test
    void onReceive_unregistered_unregistersServer() {
        ServerStatusNotification notification = mock(ServerStatusNotification.class);
        when(notification.getStatus()).thenReturn(ServerStatusNotification.Status.UNREGISTERED);
        when(notification.getId()).thenReturn("lobby-1");

        RegisteredServer registeredServer = mock(RegisteredServer.class);
        ServerInfo serverInfo = mock(ServerInfo.class);
        when(registeredServer.getServerInfo()).thenReturn(serverInfo);
        when(mockProxy.getServer("lobby-1")).thenReturn(Optional.of(registeredServer));

        handler.onReceive(notification);

        verify(mockProxy).unregisterServer(serverInfo);
    }

    @Test
    void onReceive_unregistered_serverNotRegistered_doesNothing() {
        ServerStatusNotification notification = mock(ServerStatusNotification.class);
        when(notification.getStatus()).thenReturn(ServerStatusNotification.Status.UNREGISTERED);
        when(notification.getId()).thenReturn("lobby-1");
        when(mockProxy.getServer("lobby-1")).thenReturn(Optional.empty());

        handler.onReceive(notification);

        verify(mockProxy, never()).unregisterServer(any());
    }
}
