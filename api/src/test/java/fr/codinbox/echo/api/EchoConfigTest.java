package fr.codinbox.echo.api;

import fr.codinbox.echo.api.cache.CacheProvider;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.server.ServerLoad;
import fr.codinbox.echo.api.server.ServerLoadProvider;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Tag("unit")
class EchoConfigTest {

    @Test
    void build_exposesConfiguredValues() {
        Supplier<CacheProvider> cacheProviderFactory = () -> mock(CacheProvider.class);
        Supplier<MessagingProvider> messagingProviderFactory = () -> mock(MessagingProvider.class);
        ServerLoadProvider serverLoadProvider = () -> new ServerLoad(3, true);
        ServerPlacement serverPlacement = mock(ServerPlacement.class);

        EchoConfig config = EchoConfig.builder()
                .cacheProviderFactory(cacheProviderFactory)
                .messagingProviderFactory(messagingProviderFactory)
                .resourceType(EchoResourceType.SERVER)
                .resourceId("test-server")
                .heartbeatTtlSeconds(60)
                .heartbeatIntervalSeconds(20)
                .scanIntervalSeconds(45)
                .cleanupEnabled(true)
                .serverLoadProvider(serverLoadProvider)
                .serverPlacement(serverPlacement)
                .build();

        assertThat(config.getCacheProviderFactory()).isSameAs(cacheProviderFactory);
        assertThat(config.getMessagingProviderFactory()).isSameAs(messagingProviderFactory);
        assertThat(config.getResourceType()).isEqualTo(EchoResourceType.SERVER);
        assertThat(config.getResourceId()).isEqualTo("test-server");
        assertThat(config.getHeartbeatTtlSeconds()).isEqualTo(60);
        assertThat(config.getHeartbeatIntervalSeconds()).isEqualTo(20);
        assertThat(config.getScanIntervalSeconds()).isEqualTo(45);
        assertThat(config.isCleanupEnabled()).isTrue();
        assertThat(config.getServerLoadProvider()).isSameAs(serverLoadProvider);
        assertThat(config.getServerPlacement()).isSameAs(serverPlacement);
    }

    @Test
    void build_appliesDefaultsForEachResourceType() {
        EchoConfig server = builder().build();
        EchoConfig proxy = builder().resourceType(EchoResourceType.PROXY).build();

        assertThat(server.getHeartbeatTtlSeconds()).isEqualTo(EchoConfig.DEFAULT_HEARTBEAT_TTL);
        assertThat(server.getHeartbeatIntervalSeconds()).isEqualTo(EchoConfig.DEFAULT_HEARTBEAT_INTERVAL);
        assertThat(server.getScanIntervalSeconds()).isEqualTo(EchoConfig.DEFAULT_SCAN_INTERVAL);
        assertThat(server.isCleanupEnabled()).isFalse();
        assertThat(server.getServerLoadProvider()).isNull();
        assertThat(server.getServerPlacement()).isNull();
        assertThat(proxy.isCleanupEnabled()).isTrue();
    }

    @Test
    void build_rejectsMissingRequiredValues() {
        Supplier<CacheProvider> cacheProviderFactory = () -> mock(CacheProvider.class);
        Supplier<MessagingProvider> messagingProviderFactory = () -> mock(MessagingProvider.class);

        assertThatThrownBy(() -> EchoConfig.builder().build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cacheProviderFactory");
        assertThatThrownBy(() -> EchoConfig.builder().cacheProviderFactory(cacheProviderFactory).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("messagingProviderFactory");
        assertThatThrownBy(() -> EchoConfig.builder()
                .cacheProviderFactory(cacheProviderFactory)
                .messagingProviderFactory(messagingProviderFactory)
                .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("resourceType");
        assertThatThrownBy(() -> EchoConfig.builder()
                .cacheProviderFactory(cacheProviderFactory)
                .messagingProviderFactory(messagingProviderFactory)
                .resourceType(EchoResourceType.SERVER)
                .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("resourceId");
    }

    @Test
    void serverLoadProvider_rejectsProxyResource() {
        assertThatThrownBy(() -> builder()
                .resourceType(EchoResourceType.PROXY)
                .serverLoadProvider(() -> new ServerLoad(0, true))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proxy");
    }

    @Test
    void initialProperties_whenOmitted_areEmpty() {
        assertThat(builder().build().getInitialProperties()).isEmpty();
    }

    @Test
    void initialProperties_areSnapshottedAndImmutable() {
        PropertyKey<String> serverType = new PropertyKey<>("server_type");
        Map<PropertyKey<?>, Object> properties = new HashMap<>();
        properties.put(serverType, "lobby");

        EchoConfig config = builder().initialProperties(properties).build();
        properties.put(new PropertyKey<>("region"), "eu-west");

        assertThat(config.getInitialProperties()).isEqualTo(Map.of(serverType, "lobby"));
        assertThatThrownBy(() -> config.getInitialProperties().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void initialProperties_rejectSystemKeys() {
        assertThatThrownBy(() -> builder()
                .initialProperties(Map.of(new PropertyKey<>("creation_time"), 42L))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("creation_time");
        assertThatThrownBy(() -> builder()
                .initialProperties(Map.of(new PropertyKey<>("availability"), "ACTIVE"))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("availability");
        assertThatThrownBy(() -> builder()
                .initialProperties(Map.of(new PropertyKey<>("load"), "invalid"))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("load");
    }

    private EchoConfig.Builder builder() {
        return EchoConfig.builder()
                .cacheProviderFactory(() -> mock(CacheProvider.class))
                .messagingProviderFactory(() -> mock(MessagingProvider.class))
                .resourceType(EchoResourceType.SERVER)
                .resourceId("test-server");
    }
}
