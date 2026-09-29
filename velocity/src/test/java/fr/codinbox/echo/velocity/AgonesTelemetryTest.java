package fr.codinbox.echo.velocity;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.codinbox.echo.agones.AgonesGameServerLifecycle;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgonesTelemetryTest {
    @Test
    void publishesActualCountsWithProxyThresholdInsteadOfPaperHardLimit() throws Exception {
        final EchoPlugin plugin = new EchoPlugin();
        final AgonesGameServerLifecycle sidecar = mock(AgonesGameServerLifecycle.class);
        final Instant now = Instant.now();
        final java.lang.reflect.Field lifecycle = EchoPlugin.class.getDeclaredField("agonesLifecycle");
        lifecycle.setAccessible(true);
        lifecycle.set(plugin, sidecar);
        when(sidecar.publishTelemetry(now, 4, 3, 475)).thenReturn(CompletableFuture.completedFuture(null));

        plugin.publishTelemetry(now, 4, 3);

        verify(sidecar).publishTelemetry(now, 4, 3, 475);
    }
}
