package fr.codinbox.echo.ondemand;

import fr.codinbox.echo.api.server.ServerAvailability;
import fr.codinbox.echo.ondemand.internal.OnDemandServersRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class OnDemandServersTest {

    private final OnDemandServers servers = new OnDemandServers() {
        @Override
        public CompletableFuture<ServerHandle> acquire(ServerRequest request) {
            return CompletableFuture.completedFuture(new ServerHandle("unused"));
        }

        @Override
        public CompletableFuture<Void> terminate(ServerHandle server) {
            return CompletableFuture.completedFuture(null);
        }
    };

    @AfterEach
    void unload() {
        OnDemandServersRegistry.unregister(this.servers);
    }

    @Test
    void unavailableLifecycleAndAdministrationFailExplicitly() {
        assertThatThrownBy(OnDemandServers::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OnDemandServers is not loaded");
        assertThatThrownBy(this.servers::administration)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("On-demand administration is not supported");
    }

    @Test
    void registryRejectsReplacementAndUnregistersByIdentity() {
        final OnDemandServers other = new OnDemandServers() {
            @Override
            public CompletableFuture<ServerHandle> acquire(ServerRequest request) {
                return CompletableFuture.completedFuture(new ServerHandle("unused"));
            }

            @Override
            public CompletableFuture<Void> terminate(ServerHandle server) {
                return CompletableFuture.completedFuture(null);
            }
        };

        OnDemandServersRegistry.register(this.servers);
        assertThat(OnDemandServers.load()).isSameAs(this.servers);
        assertThatThrownBy(() -> OnDemandServersRegistry.register(other))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OnDemandServers is already loaded");

        OnDemandServersRegistry.unregister(other);
        assertThat(OnDemandServers.load()).isSameAs(this.servers);
        OnDemandServersRegistry.unregister(this.servers);
        assertThatThrownBy(OnDemandServers::load).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allocationValidatesAndExposesItsImmutableState() {
        OnDemandAdministration.Allocation allocation =
                new OnDemandAdministration.Allocation("queue-1", "server-1");

        assertThat(allocation.requestId()).isEqualTo("queue-1");
        assertThat(allocation.serverId()).isEqualTo("server-1");
        assertThat(allocation).isEqualTo(new OnDemandAdministration.Allocation("queue-1", "server-1"));

        assertThatThrownBy(() -> new OnDemandAdministration.Allocation(null, "server-1"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("requestId");
        assertThatThrownBy(() -> new OnDemandAdministration.Allocation(" \t", "server-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestId must not be blank");
        assertThatThrownBy(() -> new OnDemandAdministration.Allocation("queue-1", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("serverId");
        assertThatThrownBy(() -> new OnDemandAdministration.Allocation("queue-1", " \t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("serverId must not be blank");
    }

    @Test
    void reconciliationValidatesAndExposesItsImmutableState() {
        OnDemandAdministration.Allocation allocation =
                new OnDemandAdministration.Allocation("queue-1", "server-1");
        OnDemandAdministration.Reconciliation active =
                new OnDemandAdministration.Reconciliation(allocation, true, ServerAvailability.ACTIVE);
        OnDemandAdministration.Reconciliation dead =
                new OnDemandAdministration.Reconciliation(allocation, false, null);

        assertThat(active.allocation()).isSameAs(allocation);
        assertThat(active.live()).isTrue();
        assertThat(active.availability()).isEqualTo(ServerAvailability.ACTIVE);
        assertThat(dead.live()).isFalse();
        assertThat(dead.availability()).isNull();
        assertThat(active).isEqualTo(new OnDemandAdministration.Reconciliation(
                allocation, true, ServerAvailability.ACTIVE));

        assertThatThrownBy(() -> new OnDemandAdministration.Reconciliation(
                null, false, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("allocation");
        assertThatThrownBy(() -> new OnDemandAdministration.Reconciliation(
                allocation, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only a live server can have availability");
        assertThatThrownBy(() -> new OnDemandAdministration.Reconciliation(
                allocation, false, ServerAvailability.DRAINING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only a live server can have availability");
    }
}
