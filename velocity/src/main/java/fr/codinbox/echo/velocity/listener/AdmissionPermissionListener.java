package fr.codinbox.echo.velocity.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import fr.codinbox.echo.api.server.ServerAdmissionSnapshot;
import fr.codinbox.echo.core.server.placement.RedisServerPlacement;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/** Samples permissions before routing plugins perform initial-join or direct-switch reservations. */
public final class AdmissionPermissionListener {
    private final RedisServerPlacement placement;

    public AdmissionPermissionListener(final @NotNull RedisServerPlacement placement) {
        this.placement = placement;
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onLogin(final @NotNull LoginEvent event) {
        this.publish(event.getPlayer());
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onInitialServer(final @NotNull PlayerChooseInitialServerEvent event) {
        this.publish(event.getPlayer());
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onPreConnect(final @NotNull ServerPreConnectEvent event) {
        this.publish(event.getPlayer());
    }

    private void publish(final Player player) {
        this.placement.publishStaffPermissions(Map.of(player.getUniqueId(),
                player.hasPermission(ServerAdmissionSnapshot.STAFF_PERMISSION)));
    }
}
