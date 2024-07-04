package fr.codinbox.echo.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.connector.commons.redis.RedisConnectorService;
import fr.codinbox.connector.velocity.Connector;
import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.messaging.impl.ServerStatusNotification;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.core.EchoClientImpl;
import fr.codinbox.echo.velocity.listener.JoinListener;
import fr.codinbox.echo.velocity.messaging.ProxySwitchRequestHandler;
import fr.codinbox.echo.velocity.messaging.ServerStatusNotificationHandler;
import fr.codinbox.echo.velocity.messaging.ServerSwitchRequestHandler;
import fr.codinbox.echo.velocity.utils.ProxyUtils;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

@Plugin(
        id = "echo",
        name = "Echo",
        version = "1.0.0-SNAPSHOT",
        authors = {"dandan2611"},
        dependencies = {
                @Dependency(id = "connector", optional = false)
        }
)
public class EchoPlugin {

    public static final @NotNull String ECHO_CONNECTOR_CONNECTION_NAME = "ECHO";

    @Inject
    private Logger logger;

    @Inject
    private ProxyServer proxy;

    @Subscribe
    private void onProxyInit(final @NotNull ProxyInitializeEvent event) {
        try {
            final RedisConnectorService redisConnectorService = Connector.getRedisService();
            final Optional<RedisConnection> echoConnection = redisConnectorService.getConnection(ECHO_CONNECTOR_CONNECTION_NAME);

            if (echoConnection.isEmpty())
                throw new IllegalStateException("Failed to get Redis connection for Echo, is the " + ECHO_CONNECTOR_CONNECTION_NAME + " connection property configured?");

            final EchoClient client = EchoClientImpl.autoInit(echoConnection.get(), EchoResourceType.PROXY);

            // Dynamic server registration
            final MessagingProvider messagingProvider = client.getMessagingProvider();;
            messagingProvider.subscribe(client.getLocalTopic(), new ServerStatusNotificationHandler(this.logger, this.proxy));
            messagingProvider.subscribe(client.getLocalTopic(), new ServerSwitchRequestHandler(this.logger, this.proxy));
            messagingProvider.subscribe(client.getLocalTopic(), new ProxySwitchRequestHandler(this.logger, this.proxy));

            // Load existing servers
            client.getServers().thenAccept(servers -> {
                for (String s : servers.keySet()) {
                    client.getServerById(s).thenAccept(server -> {
                        if (server != null) {
                            ProxyUtils.registerServer(this.proxy, this.logger, server.getId(), server.getAddress());
                        }
                    });
                }
            });

            // Register listeners
            final EventManager eventManager = this.proxy.getEventManager();
            eventManager.register(this, new JoinListener(this.proxy));

        } catch (Exception e) {
            this.logger.log(Level.SEVERE, "Failed to initialize Echo client", e);
            this.proxy.shutdown();
            return;
        }
    }

    @Subscribe(order = PostOrder.LATE)
    private void onProxyShutdown(final @NotNull ProxyShutdownEvent event) {
        final EchoClient client = Echo.getClient();
        client.shutdown();
    }

}
