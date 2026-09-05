package fr.codinbox.echo.velocity.listener;

import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.server.ServerAdmissionSnapshot;
import fr.codinbox.echo.core.server.placement.RedisServerPlacement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RBatch;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class AdmissionPermissionListenerTest {
    @Test
    void loginPublishesPermissionBeforeInitialReservationHandlers() {
        final Fixture fixture = new Fixture(true);
        final LoginEvent event = mock(LoginEvent.class);
        when(event.getPlayer()).thenReturn(fixture.player);

        fixture.listener.onLogin(event);

        verify(fixture.bucket).setAsync(true, Duration.ofSeconds(5));
    }

    @Test
    void initialRoutingPublishesServerEvaluatedStaffPermission() {
        final Fixture fixture = new Fixture(true);
        final PlayerChooseInitialServerEvent event = mock(PlayerChooseInitialServerEvent.class);
        when(event.getPlayer()).thenReturn(fixture.player);

        fixture.listener.onInitialServer(event);

        verify(fixture.bucket).setAsync(true, Duration.ofSeconds(5));
    }

    @Test
    void directSwitchPublishesNonStaffWithoutAcceptingCallerClassification() {
        final Fixture fixture = new Fixture(false);
        final ServerPreConnectEvent event = mock(ServerPreConnectEvent.class);
        when(event.getPlayer()).thenReturn(fixture.player);

        fixture.listener.onPreConnect(event);

        verify(fixture.bucket).setAsync(false, Duration.ofSeconds(5));
    }

    private static final class Fixture {
        private final Player player = mock(Player.class);
        private final RBucket<Boolean> bucket = mock(RBucket.class);
        private final AdmissionPermissionListener listener;

        private Fixture(final boolean staff) {
            final RedisConnection connection = mock(RedisConnection.class);
            final RedissonClient redis = mock(RedissonClient.class);
            final UUID id = UUID.randomUUID();
            when(connection.getClient()).thenReturn(redis);
            when(player.getUniqueId()).thenReturn(id);
            when(player.hasPermission(ServerAdmissionSnapshot.STAFF_PERMISSION)).thenReturn(staff);
            final RBatch batch = mock(RBatch.class);
            when(redis.createBatch()).thenReturn(batch);
            when(batch.<Boolean>getBucket("admission:staff:" + id)).thenReturn(bucket);
            listener = new AdmissionPermissionListener(new RedisServerPlacement(connection));
        }
    }
}
