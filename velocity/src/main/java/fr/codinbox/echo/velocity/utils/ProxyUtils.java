package fr.codinbox.echo.velocity.utils;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import fr.codinbox.echo.api.server.Address;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
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

}
