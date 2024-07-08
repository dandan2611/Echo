package fr.codinbox.echo.paper.listener;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.user.User;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
            if (user == null) { // Create the user if it doesn't exist
                client.createUser(player.getUniqueId(), player.getName(), currentResourceId);
                return;

            }
            final String currentServerId = user.getCurrentServerId().join();

            if (currentServerId != null)
                user.setPreviousServerId(currentServerId);

            user.setProperty(User.PROPERTY_CURRENT_SERVER_ID, currentResourceId);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onQuit(final @NotNull PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        final EchoClient client = Echo.getClient();

        // If there is no proxy, destroy the user by ourselves
        client.getProxies().thenAccept(proxyMap -> {
            if (proxyMap.isEmpty()) {
                client.getUserById(player.getUniqueId()).thenCompose(user -> {
                    if (user == null)
                        return null;
                    return client.destroyUser(user);
                });
            }
        });
    }

}
