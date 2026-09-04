package fr.codinbox.echo.velocity.utils;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerAvailability;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public final class ProxyUtils {

    public static void registerServer(final @NotNull ProxyServer proxy,
                                      final @NotNull Logger logger,
                                      final @NotNull String id,
                                      final @NotNull Address address) {
        final ServerInfo info = new ServerInfo(id, new InetSocketAddress(address.getHost(), address.getPort()));
        proxy.registerServer(info);
        logger.info("Registered server '" + id + "'");
    }

    public static void unregisterServer(final @NotNull ProxyServer proxy,
                                        final @NotNull Logger logger,
                                        final @NotNull String id) {
        final RegisteredServer registeredServer = proxy.getServer(id).orElse(null);
        if (registeredServer == null)
            return;
        proxy.unregisterServer(registeredServer.getServerInfo());
        logger.info("Unregistered server '" + id + "'");
    }

    public static @NotNull CompletableFuture<Void> registerServerIfActive(
            final @NotNull ProxyServer proxy,
            final @NotNull Logger logger,
            final @NotNull Server server) {
        return server.getAvailability().thenCompose(availability -> {
            applyAvailability(proxy, logger, server, availability);
            return server.getAvailability().thenAccept(current -> {
                if (current != availability)
                    applyAvailability(proxy, logger, server, current);
            });
        });
    }

    private static void applyAvailability(final ProxyServer proxy,
                                          final Logger logger,
                                          final Server server,
                                          final ServerAvailability availability) {
        if (availability == ServerAvailability.ACTIVE)
            registerServer(proxy, logger, server.getId(), server.getAddress());
        else
            unregisterServer(proxy, logger, server.getId());
    }

}
