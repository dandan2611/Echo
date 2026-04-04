package fr.codinbox.echo.core.property;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.cache.CacheProvider;
import fr.codinbox.echo.core.testutils.EchoTestUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class AbstractPropertyHolderTest {

    private static class TestPropertyHolder extends AbstractPropertyHolder<String> {
        TestPropertyHolder(String id) {
            super(id, "test:" + id);
        }
    }

    @BeforeEach
    void setUp() {
        EchoTestUtils.resetEchoClient();
    }

    @Test
    void setProperty_shouldDelegateToCacheSetObject() {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            EchoClient mockClient = mock(EchoClient.class);
            CacheProvider mockCache = mock(CacheProvider.class);
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCacheProvider()).thenReturn(mockCache);
            when(mockCache.setObject(anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

            TestPropertyHolder holder = new TestPropertyHolder("myId");
            holder.setProperty("key", "value");

            verify(mockCache).setObject("test:myId:property:key", "value");
        }
    }

    @Test
    void setProperty_withNullValue_shouldCallDeleteProperty() {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            EchoClient mockClient = mock(EchoClient.class);
            CacheProvider mockCache = mock(CacheProvider.class);
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCacheProvider()).thenReturn(mockCache);
            when(mockCache.deleteObject(anyString())).thenReturn(CompletableFuture.completedFuture(true));

            TestPropertyHolder holder = new TestPropertyHolder("myId");
            holder.setProperty("key", null);

            verify(mockCache).deleteObject("test:myId:property:key");
        }
    }

    @Test
    void getProperty_shouldDelegateToCacheGetObject() {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            EchoClient mockClient = mock(EchoClient.class);
            CacheProvider mockCache = mock(CacheProvider.class);
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCacheProvider()).thenReturn(mockCache);
            when(mockCache.<String>getObject("test:myId:property:key"))
                    .thenReturn(CompletableFuture.completedFuture("found"));

            TestPropertyHolder holder = new TestPropertyHolder("myId");
            Optional<String> result = holder.<String>getProperty("key").join();

            assertThat(result).contains("found");
        }
    }

    @Test
    void deleteProperty_shouldDelegateToCacheDeleteObject() {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            EchoClient mockClient = mock(EchoClient.class);
            CacheProvider mockCache = mock(CacheProvider.class);
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCacheProvider()).thenReturn(mockCache);
            when(mockCache.deleteObject("test:myId:property:key"))
                    .thenReturn(CompletableFuture.completedFuture(true));

            TestPropertyHolder holder = new TestPropertyHolder("myId");
            boolean result = holder.deleteProperty("key").join();

            assertThat(result).isTrue();
            verify(mockCache).deleteObject("test:myId:property:key");
        }
    }

    @Test
    void hasProperty_shouldDelegateToCacheHasObject() {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            EchoClient mockClient = mock(EchoClient.class);
            CacheProvider mockCache = mock(CacheProvider.class);
            echoMock.when(Echo::getClient).thenReturn(mockClient);
            when(mockClient.getCacheProvider()).thenReturn(mockCache);
            when(mockCache.hasObject("test:myId:property:key"))
                    .thenReturn(CompletableFuture.completedFuture(true));

            TestPropertyHolder holder = new TestPropertyHolder("myId");
            boolean result = holder.hasProperty("key").join();

            assertThat(result).isTrue();
            verify(mockCache).hasObject("test:myId:property:key");
        }
    }

    @Test
    void getId_shouldReturnCorrectId() {
        try (MockedStatic<Echo> echoMock = mockStatic(Echo.class)) {
            TestPropertyHolder holder = new TestPropertyHolder("myId");

            assertThat(holder.getId()).isEqualTo("myId");
        }
    }
}
