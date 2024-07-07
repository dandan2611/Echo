package fr.codinbox.echo.paper;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.connector.commons.redis.RedisConnectorService;
import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.core.EchoClientImpl;
import fr.codinbox.echo.paper.listener.JoinListener;
import fr.codinbox.echo.paper.messaging.SendMessageHandler;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;

public class EchoPaper extends JavaPlugin {

    public static final @NotNull String ECHO_CONNECTOR_CONNECTION_NAME = "ECHO";

    @Override
    public void onLoad() {

    }

    @Override
    public void onEnable() {
        try {
            final RedisConnectorService redisConnectorService = Objects.requireNonNull(this.getServer().getServicesManager().load(RedisConnectorService.class));
            final Optional<RedisConnection> echoConnection = redisConnectorService.getConnection(ECHO_CONNECTOR_CONNECTION_NAME);

            if (echoConnection.isEmpty())
                throw new IllegalStateException("Failed to get Redis connection for Echo, is the " + ECHO_CONNECTOR_CONNECTION_NAME + " connection property configured?");

            final EchoClient client = EchoClientImpl.autoInit(echoConnection.get(), EchoResourceType.SERVER);

            // Register listeners
            final PluginManager pluginManager = super.getServer().getPluginManager();
            pluginManager.registerEvents(new JoinListener(), this);

            // Message listeners
            final MessagingProvider messagingProvider = client.getMessagingProvider();
            messagingProvider.subscribe(client.getLocalTopic(), new SendMessageHandler());
        } catch (Exception e) {
            super.getLogger().log(Level.SEVERE, "Failed to initialize Echo client", e);
            super.getServer().shutdown();
            return;
        }
    }

    @Override
    public void onDisable() {
        final EchoClient client = Echo.getClient();
        client.shutdown();
    }

}
