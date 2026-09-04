package fr.codinbox.echo.velocity.utils;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerAvailability;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.logging.Logger;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ProxyUtilsTest {

    @Mock
    private ProxyServer mockProxy;
    @Mock
    private Logger mockLogger;

    @Test
    void registerServer_callsProxyRegisterServer() {
        Address address = new Address("192.168.1.1", 25565);

        ProxyUtils.registerServer(mockProxy, mockLogger, "survival-1", address);

        ArgumentCaptor<ServerInfo> captor = ArgumentCaptor.forClass(ServerInfo.class);
        verify(mockProxy).registerServer(captor.capture());

        ServerInfo info = captor.getValue();
        assertThat(info.getName()).isEqualTo("survival-1");
        assertThat(info.getAddress().getHostName()).isEqualTo("192.168.1.1");
        assertThat(info.getAddress().getPort()).isEqualTo(25565);
    }

    @Test
    void registerServerIfActive_removesServerWhenItStartsDrainingDuringRegistration() {
        Server server = mock(Server.class);
        RegisteredServer registeredServer = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(server.getId()).thenReturn("survival-1");
        when(server.getAddress()).thenReturn(new Address("192.168.1.1", 25565));
        when(server.getAvailability()).thenReturn(
                EchoFuture.completed(ServerAvailability.ACTIVE),
                EchoFuture.completed(ServerAvailability.DRAINING));
        when(mockProxy.getServer("survival-1")).thenReturn(Optional.of(registeredServer));
        when(registeredServer.getServerInfo()).thenReturn(info);

        ProxyUtils.registerServerIfActive(mockProxy, mockLogger, server).join();

        verify(mockProxy).registerServer(org.mockito.ArgumentMatchers.any(ServerInfo.class));
        verify(mockProxy).unregisterServer(info);
    }

    @Test
    void registerServerIfActive_registersServerWhenItBecomesActiveDuringLoading() {
        Server server = mock(Server.class);
        when(server.getId()).thenReturn("survival-1");
        when(server.getAddress()).thenReturn(new Address("192.168.1.1", 25565));
        when(server.getAvailability()).thenReturn(
                EchoFuture.completed(ServerAvailability.DRAINING),
                EchoFuture.completed(ServerAvailability.ACTIVE));

        ProxyUtils.registerServerIfActive(mockProxy, mockLogger, server).join();

        verify(mockProxy).registerServer(org.mockito.ArgumentMatchers.any(ServerInfo.class));
    }
}
