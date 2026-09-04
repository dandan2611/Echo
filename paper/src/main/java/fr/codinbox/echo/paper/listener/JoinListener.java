package fr.codinbox.echo.paper.listener;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerLoadManager;
import fr.codinbox.echo.paper.EchoPaper;
import fr.codinbox.echo.api.user.User;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.logging.Level;

public class JoinListener implements Listener {

    private final @NotNull EchoPaper plugin;
    private final @NotNull ServerLoadManager loadManager;

    public JoinListener(final @NotNull EchoPaper plugin, final @NotNull ServerLoadManager loadManager) {
        this.plugin = plugin;
        this.loadManager = loadManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onJoin(final @NotNull PlayerJoinEvent event) {
        this.scheduleLoadRefresh();
        final Player player = event.getPlayer();
        final EchoClient client = Echo.getClient();

        final String currentResourceId = client.getCurrentResourceId().orElse(null);
        if (currentResourceId == null)
            return;

        client.getUserById(player.getUniqueId()).thenAcceptAsync(userOpt -> {
            if (userOpt.isEmpty()) {
                client.createUser(player.getUniqueId(), player.getName(), currentResourceId);
                return;
            }

            final User user = userOpt.get();

            // Set user previous server ID in a non-blocking way
            user.getCurrentServerId().thenAccept(currentServerIdOpt -> {
                currentServerIdOpt.ifPresent(s -> user.setPreviousServerId(s));
            });

            final Optional<Server> echoServerOpt = client.getServerById(currentResourceId).await();

            if (echoServerOpt.isEmpty())
                return;

            client.registerUserInServer(user, echoServerOpt.get());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onQuit(final @NotNull PlayerQuitEvent event) {
        this.scheduleLoadRefresh();
        final Player player = event.getPlayer();
        final EchoClient client = Echo.getClient();

        // If there is no proxy, destroy the user by ourselves
        client.getProxies().thenAccept(proxyMap -> {
            if (!proxyMap.isEmpty())
                return;

            client.getUserById(player.getUniqueId()).thenAccept(userOpt -> {
                if (userOpt.isEmpty())
                    return;
                client.destroyUser(userOpt.get());
            });
        });
    }

    private void scheduleLoadRefresh() {
        this.plugin.getServer().getScheduler().runTask(this.plugin,
                () -> this.loadManager.refresh().whenComplete((ignored, error) -> {
                    if (error != null)
                        this.plugin.getLogger().log(Level.WARNING, "Failed to refresh Echo server load", error);
                }));
    }

}
