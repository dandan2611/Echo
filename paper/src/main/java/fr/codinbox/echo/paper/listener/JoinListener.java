package fr.codinbox.echo.paper.listener;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.user.User;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

public class JoinListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    private void onJoin(final @NotNull PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final EchoClient client = Echo.getClient();

        final String currentResourceId = client.getCurrentResourceId().orElse(null);
        if (currentResourceId == null)
            return;

        client.getUserById(player.getUniqueId()).thenAccept(user -> {
            if (user == null)
                return;
            final String currentServerId = user.getCurrentServerId().join();

            if (currentServerId != null)
                user.setPreviousServerId(currentServerId);

            user.setProperty(User.PROPERTY_CURRENT_SERVER_ID, currentResourceId);
        });
    }

}
