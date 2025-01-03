package fr.codinbox.echo.velocity.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import org.jetbrains.annotations.NotNull;

public class JoinListener {

    private final @NotNull ProxyServer proxy;

    public JoinListener(@NotNull ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Subscribe(order = PostOrder.FIRST)
    private void onLogin(final @NotNull LoginEvent event) {
        final Player player = event.getPlayer();
        final EchoClient client = Echo.getClient();

        final String currentResourceId = client.getCurrentResourceId().orElse(null);
        if (currentResourceId == null)
            return;

        client.createUserAsync(player.getUniqueId(), player.getUsername(), currentResourceId);
    }

    @Subscribe(order = PostOrder.LAST)
    private void onDisconnect(final @NotNull DisconnectEvent event) {
        final Player player = event.getPlayer();
        final EchoClient client = Echo.getClient();

        client.getUserByIdAsync(player.getUniqueId()).thenAccept(userOpt -> {
            if (userOpt.isEmpty())
                return;
            client.destroyUserAsync(userOpt.get());
        });
    }

}
