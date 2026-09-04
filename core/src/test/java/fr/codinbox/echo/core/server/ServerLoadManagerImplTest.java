package fr.codinbox.echo.core.server;

import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerLoad;
import fr.codinbox.echo.api.server.ServerLoadManager;
import fr.codinbox.echo.api.server.ServerLoadProvider;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Tag("unit")
class ServerLoadManagerImplTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    private Server server;

    @BeforeEach
    void setUp() {
        server = mock(Server.class);
        when(server.setProperty(eq(Server.PROPERTY_LOAD), any(ServerLoadSnapshot.class)))
                .thenReturn(EchoFuture.completed(null));
    }

    @Test
    void refresh_persistsSnapshotFromDefaultProvider() {
        ServerLoadManagerImpl manager = manager(() -> new ServerLoad(3, true));

        ServerLoadSnapshot result = manager.refresh().join();

        ArgumentCaptor<ServerLoadSnapshot> snapshot = ArgumentCaptor.forClass(ServerLoadSnapshot.class);
        verify(server).setProperty(eq(Server.PROPERTY_LOAD), snapshot.capture());
        assertThat(snapshot.getValue()).isEqualTo(result);
        assertThat(result.load()).isEqualTo(new ServerLoad(3, true));
        assertThat(result.sampledAt()).isEqualTo(NOW);
        assertThat(result.validUntil()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void providerRegistration_closeRestoresDefaultAndRejectsSecondOverride() {
        ServerLoadManagerImpl manager = manager(() -> new ServerLoad(1, true));
        ServerLoadManager.ProviderRegistration registration =
                manager.setProvider(() -> new ServerLoad(7, false));

        assertThat(manager.refresh().join().load()).isEqualTo(new ServerLoad(7, false));
        assertThatThrownBy(() -> manager.setProvider(() -> new ServerLoad(8, true)))
                .isInstanceOf(IllegalStateException.class);

        registration.close();
        registration.close();
        assertThat(manager.refresh().join().load()).isEqualTo(new ServerLoad(1, true));
    }

    @Test
    void providerRegistration_oldHandleCannotCloseLaterRegistrationOfSameProvider() {
        ServerLoadProvider provider = () -> new ServerLoad(7, false);
        ServerLoadManagerImpl manager = manager(() -> new ServerLoad(1, true));
        ServerLoadManager.ProviderRegistration first = manager.setProvider(provider);
        first.close();
        ServerLoadManager.ProviderRegistration second = manager.setProvider(provider);

        first.close();

        assertThat(manager.refresh().join().load()).isEqualTo(new ServerLoad(7, false));
        second.close();
    }

    @Test
    void refresh_providerFailureDoesNotOverwriteLastSnapshot() {
        ServerLoadManagerImpl manager = manager(() -> new ServerLoad(2, true));
        manager.refresh().join();
        manager.setProvider(() -> {
            throw new IllegalStateException("provider failed");
        });

        assertThatThrownBy(() -> manager.refresh().join()).hasRootCauseMessage("provider failed");
        verify(server, times(1)).setProperty(eq(Server.PROPERTY_LOAD), any(ServerLoadSnapshot.class));
    }

    @Test
    void constructor_rejectsNonPositiveStaleness() {
        assertThatThrownBy(() -> new ServerLoadManagerImpl(server, null, Duration.ZERO,
                Clock.fixed(NOW, ZoneOffset.UTC))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServerLoadManagerImpl(server, null, Duration.ofSeconds(-1),
                Clock.fixed(NOW, ZoneOffset.UTC))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refresh_withoutProviderFails() {
        assertThatThrownBy(() -> manager(null).refresh().join())
                .hasRootCauseMessage("No server load provider is registered");
        verify(server, never()).setProperty(eq(Server.PROPERTY_LOAD), any(ServerLoadSnapshot.class));
    }

    @Test
    void refresh_propagatesPersistenceFailure() {
        EchoFuture<Void> failure = new EchoFuture<>();
        failure.completeExceptionally(new IllegalStateException("write failed"));
        when(server.setProperty(eq(Server.PROPERTY_LOAD), any(ServerLoadSnapshot.class))).thenReturn(failure);

        assertThatThrownBy(() -> manager(() -> new ServerLoad(1, true)).refresh().join())
                .hasRootCauseMessage("write failed");
    }

    @Test
    void current_readsPersistedSnapshot() {
        ServerLoadSnapshot snapshot = new ServerLoadSnapshot(
                new ServerLoad(5, false), NOW, NOW.plusSeconds(30));
        when(server.getLoad()).thenReturn(EchoFuture.completed(Optional.of(snapshot)));

        assertThat(manager(() -> new ServerLoad(0, true)).getCurrent().join())
                .contains(snapshot);
    }

    private ServerLoadManagerImpl manager(ServerLoadProvider defaultProvider) {
        return new ServerLoadManagerImpl(server, defaultProvider, Duration.ofSeconds(30),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
