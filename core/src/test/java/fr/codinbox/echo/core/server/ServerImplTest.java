package fr.codinbox.echo.core.server;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.cache.CacheProvider;
import fr.codinbox.echo.core.testutils.EchoTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ServerImplTest {

    @BeforeEach
    void setUp() {
        EchoTestUtils.resetEchoClient();
    }

    @Test
    void constructor_withNullAddress_shouldNotCallCache() {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            EchoClient mockClient = mock(EchoClient.class);
            echoMock.when(Echo::getClient).thenReturn(mockClient);

            new ServerImpl("testServer", null);

            verify(mockClient, never()).getCacheProvider();
        }
    }

    @Test
    void constants_shouldHaveCorrectValues() {
        assertThat(ServerImpl.SERVER_MAP).isEqualTo("servers:map");
        assertThat(ServerImpl.SERVER_TOPIC).isEqualTo("server:%s");
        assertThat(ServerImpl.SERVER_KEY).isEqualTo("server:%s");
        assertThat(ServerImpl.SERVER_ADDRESS_KEY).isEqualTo("server:%s:address");
    }

    @Test
    void stillExists_shouldCheckHeartbeatKey() {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            EchoClient mockClient = mock(EchoClient.class);
            CacheProvider mockCache = mock(CacheProvider.class);
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCacheProvider()).thenReturn(mockCache);
            when(mockCache.hasObject("heartbeat:server:testServer"))
                    .thenReturn(CompletableFuture.completedFuture(true));

            ServerImpl server = new ServerImpl("testServer", null);
            boolean result = server.stillExists().join();

            assertThat(result).isTrue();
            verify(mockCache).hasObject("heartbeat:server:testServer");
        }
    }

    @Test
    void heartbeatKey_shouldHaveCorrectFormat() {
        assertThat(ServerImpl.HEARTBEAT_KEY).isEqualTo("heartbeat:server:%s");
    }
}
