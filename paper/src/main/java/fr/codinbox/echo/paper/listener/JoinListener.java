package fr.codinbox.echo.paper.listener;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.server.Server;
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

        client.getUserByIdAsync(player.getUniqueId()).thenAcceptAsync(userOpt -> {
            if (userOpt.isEmpty()) { // Create the user if it doesn't exist
                client.createUserAsync(player.getUniqueId(), player.getName(), currentResourceId);
                return;

            }

            final User user = userOpt.get();

            // Set user previous server ID in a non-blocking way
            user.getCurrentServerIdAsync().thenAccept(currentServerIdOpt -> {
                currentServerIdOpt.ifPresent(user::setPreviousServerId);
            });

            user.setPropertyAsync(User.PROPERTY_CURRENT_SERVER_ID, currentResourceId);

            final Optional<Server> echoServerOpt = client.getServerById(currentResourceId);

            if (echoServerOpt.isEmpty())
                return;

            client.registerUserInServerAsync(user, echoServerOpt.get());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onQuit(final @NotNull PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        final EchoClient client = Echo.getClient();

        // If there is no proxy, destroy the user by ourselves
        client.getProxiesAsync().thenAccept(proxyMap -> {
            if (!proxyMap.isEmpty())
                return;

            client.getUserByIdAsync(player.getUniqueId()).thenAccept(userOpt -> {
                if (userOpt.isEmpty())
                    return;
                client.destroyUser(userOpt.get());
            });
        });
    }

}
