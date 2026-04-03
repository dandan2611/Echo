package fr.codinbox.echo.velocity.utils;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import fr.codinbox.echo.api.server.Address;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

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
}
