package fr.codinbox.echo.core.server;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.cache.CacheProvider;
import fr.codinbox.echo.api.server.ServerAvailability;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.core.testutils.EchoTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@Tag("unit")
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
    void constructor_withAddress_propagatesWriteFailure() {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            EchoClient mockClient = mock(EchoClient.class);
            CacheProvider mockCache = mock(CacheProvider.class);
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCacheProvider()).thenReturn(mockCache);
            when(mockCache.setObject(eq("server:testServer:address"), any(Address.class)))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("write failed")));

            assertThatThrownBy(() -> new ServerImpl("testServer", new Address("127.0.0.1", 25565)))
                    .hasRootCauseMessage("write failed");
        }
    }

    @Test
    void getAvailability_withoutStoredValue_defaultsToActive() {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            EchoClient mockClient = mock(EchoClient.class);
            CacheProvider mockCache = mock(CacheProvider.class);
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCacheProvider()).thenReturn(mockCache);
            when(mockCache.<String>getObject("server:testServer:property:availability"))
                    .thenReturn(CompletableFuture.completedFuture(null));

            assertThat(new ServerImpl("testServer", null).getAvailability().join())
                    .isEqualTo(ServerAvailability.ACTIVE);
        }
    }

    @Test
    void getAvailability_withStoredValue_returnsIt() {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            EchoClient mockClient = mock(EchoClient.class);
            CacheProvider mockCache = mock(CacheProvider.class);
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCacheProvider()).thenReturn(mockCache);
            when(mockCache.<String>getObject("server:testServer:property:availability"))
                    .thenReturn(CompletableFuture.completedFuture("DRAINING"));

            assertThat(new ServerImpl("testServer", null).getAvailability().join())
                    .isEqualTo(ServerAvailability.DRAINING);
        }
    }
}
