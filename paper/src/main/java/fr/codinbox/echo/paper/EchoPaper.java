package fr.codinbox.echo.paper;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.connector.commons.redis.RedisConnectorService;
import fr.codinbox.echo.agones.AgonesGameServerLifecycle;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoConfig;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.impl.ResourceControlRequest;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.server.ServerAvailability;
import fr.codinbox.echo.api.server.ServerLoad;
import fr.codinbox.echo.api.server.ServerLoadManager;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import fr.codinbox.echo.api.utils.EnvUtils;
import fr.codinbox.echo.commands.CommandAudience;
import fr.codinbox.echo.commands.EchoCommands;
import fr.codinbox.echo.core.EchoClientImpl;
import fr.codinbox.echo.core.RedisProviderFactory;
import fr.codinbox.echo.paper.event.ServerDrainEvent;
import fr.codinbox.echo.paper.listener.JoinListener;
import fr.codinbox.echo.paper.messaging.QueuePlacementPrepareRequestHandler;
import fr.codinbox.echo.paper.messaging.ResourceControlRequestHandler;
import fr.codinbox.echo.queue.QueuePlacementPreparer;
import fr.codinbox.echo.queue.messaging.QueuePlacementPrepareRequest;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class EchoPaper extends JavaPlugin {

    public static final @NotNull String ECHO_CONNECTOR_CONNECTION_NAME = "ECHO";
    static final String COMMAND_ROOT = "echo|echoserver";
    private static final Duration DRAIN_TIMEOUT = Duration.ofMinutes(30);
    private static final long DRAIN_CHECK_PERIOD_TICKS = 20L;
    private EchoClient echoClient;
    private AgonesGameServerLifecycle agonesLifecycle;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean draining = new AtomicBoolean();
    private Clock clock = Clock.systemUTC();

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

            final RedisConnection connection = echoConnection.get();
            final EchoConfig config = EchoConfig.builder()
                    .cacheProviderFactory(RedisProviderFactory.cacheFactory(connection))
                    .messagingProviderFactory(RedisProviderFactory.messagingFactory(connection))
                     .serverPlacement(RedisProviderFactory.serverPlacement(connection))
                     .resourceType(EchoResourceType.SERVER)
                     .resourceId(Objects.requireNonNull(EnvUtils.getResourceId()))
                     .initialProperties(initialProperties(
                             EnvUtils.getInitialProperties(), this.getServer().getMaxPlayers()))
                     .serverLoadProvider(() -> new ServerLoad(this.getServer().getOnlinePlayers().size(), true))
                     .build();
            final boolean agonesEnabled = Boolean.parseBoolean(System.getenv("ECHO_AGONES_ENABLED"));
            this.echoClient = EchoClientImpl.autoInit(config,
                    agonesEnabled ? ServerAvailability.DRAINING : ServerAvailability.ACTIVE);

            final ServerLoadManager serverLoadManager = this.echoClient.getServerLoadManager();

            // Register listeners
            final PluginManager pluginManager = super.getServer().getPluginManager();
            pluginManager.registerEvents(new JoinListener(this, serverLoadManager), this);
            this.echoClient.getMessagingProvider().subscribe(this.echoClient.getLocalTopic(),
                    QueuePlacementPrepareRequest.class, new QueuePlacementPrepareRequestHandler(
                            this, this.echoClient, this.stopping::get,
                            () -> this.getServer().getServicesManager().load(QueuePlacementPreparer.class)));
            this.echoClient.getMessagingProvider().subscribe(this.echoClient.getLocalTopic(),
                    ResourceControlRequest.class, new ResourceControlRequestHandler(this, this.echoClient));
            final PaperCommandManager<CommandSourceStack> commandManager = PaperCommandManager.builder()
                    .executionCoordinator(ExecutionCoordinator.simpleCoordinator())
                    .buildOnEnable(this);
            final AnnotationParser<CommandSourceStack> commandParser =
                    new AnnotationParser<>(commandManager, CommandSourceStack.class);
            new EchoCommands<>(this.echoClient, commandAudience(), COMMAND_ROOT).register(commandParser);
            final long refreshTicks = Math.max(1L, config.getHeartbeatIntervalSeconds() * 20L);
            this.getServer().getScheduler().runTaskTimer(this,
                    () -> serverLoadManager.refresh().whenComplete((ignored, error) -> {
                        if (error != null)
                            this.getLogger().log(Level.WARNING, "Failed to refresh Echo server load", error);
                    }), refreshTicks, refreshTicks);

            if (agonesEnabled) {
                final boolean longLived = Boolean.parseBoolean(System.getenv("ECHO_AGONES_LONG_LIVED"));
                final String drainAnnotation = Optional.ofNullable(System.getenv("ECHO_AGONES_DRAIN_ANNOTATION"))
                        .orElse(AgonesGameServerLifecycle.DEFAULT_DRAIN_ANNOTATION);
                this.agonesLifecycle = AgonesGameServerLifecycle.inPod(longLived, drainAnnotation,
                        () -> this.beginDrain().whenComplete((ignored, error) -> {
                                    if (error != null)
                                        getLogger().log(Level.SEVERE, "Failed to drain Echo server", error);
                                }));
                this.agonesLifecycle.start()
                        .thenCompose(ignored -> this.agonesLifecycle.watchForDrainRequests())
                        .thenCompose(drainRequested -> this.stopping.get() || drainRequested
                                ? CompletableFuture.completedFuture(null)
                                : this.activate().thenApply(ignored -> null))
                        .exceptionally(error -> {
                            getLogger().log(Level.SEVERE, "Failed to initialize Agones lifecycle", error);
                            getServer().shutdown();
                            return null;
                        });
            }
        } catch (Exception e) {
            super.getLogger().log(Level.SEVERE, "Failed to initialize Echo client", e);
            super.getServer().shutdown();
            return;
        }
    }

    static Map<PropertyKey<?>, Object> initialProperties(
            Map<? extends PropertyKey<?>, ?> configured, int capacity) {
        if (capacity <= 0)
            throw new IllegalArgumentException("placement capacity must be positive");
        final Map<PropertyKey<?>, Object> properties = new HashMap<>();
        configured.forEach(properties::put);
        properties.put(ServerPlacement.PROPERTY_CAPACITY, capacity);
        return Map.copyOf(properties);
    }

    static CommandAudience<CommandSourceStack> commandAudience() {
        return new CommandAudience<>() {
            @Override
            public void send(CommandSourceStack source, net.kyori.adventure.text.Component message) {
                source.getSender().sendMessage(message);
            }

            @Override
            public String identity(CommandSourceStack source) {
                return source.getSender().getName();
            }
        };
    }

    public CompletableFuture<Void> beginDrain() {
        return this.beginDrain(this.clock.instant().plus(DRAIN_TIMEOUT));
    }

    public boolean isDraining() {
        return this.draining.get();
    }

    public CompletableFuture<Void> beginDrain(final @NotNull Instant deadline) {
        Objects.requireNonNull(deadline, "deadline");
        if (this.stopping.get())
            return CompletableFuture.failedFuture(new IllegalStateException("Server is stopping"));
        if (!this.draining.compareAndSet(false, true))
            return CompletableFuture.completedFuture(null);
        return this.echoClient.setLocalServerAvailability(ServerAvailability.DRAINING)
                .thenCompose(ignored -> {
                    final CompletableFuture<Void> started = new CompletableFuture<>();
                    try {
                        this.getServer().getScheduler().runTask(this, () -> {
                            try {
                                this.getServer().getPluginManager().callEvent(new ServerDrainEvent(deadline));
                                this.scheduleDrainShutdown(deadline);
                                started.complete(null);
                            } catch (RuntimeException error) {
                                started.completeExceptionally(error);
                            }
                        });
                    } catch (RuntimeException error) {
                        started.completeExceptionally(error);
                    }
                    return started;
                }).whenComplete((ignored, error) -> {
                    if (error != null)
                        this.draining.set(false);
                });
    }

    public CompletableFuture<ServerLoadSnapshot> refreshLoad() {
        CompletableFuture<ServerLoadSnapshot> refreshed = new CompletableFuture<>();
        try {
            this.getServer().getScheduler().runTask(this, () -> {
                try {
                    this.echoClient.getServerLoadManager().refresh().whenComplete((snapshot, error) -> {
                        if (error != null)
                            refreshed.completeExceptionally(error);
                        else
                            refreshed.complete(snapshot);
                    });
                } catch (RuntimeException error) {
                    refreshed.completeExceptionally(error);
                }
            });
        } catch (RuntimeException error) {
            refreshed.completeExceptionally(error);
        }
        return refreshed;
    }

    public CompletableFuture<Boolean> activate() {
        if (this.stopping.get() || this.draining.get())
            return CompletableFuture.completedFuture(false);
        return this.echoClient.setLocalServerAvailability(ServerAvailability.ACTIVE)
                .thenCompose(ignored -> this.stopping.get() || this.draining.get()
                        ? this.echoClient.setLocalServerAvailability(ServerAvailability.DRAINING)
                                .thenApply(reset -> false)
                        : CompletableFuture.completedFuture(true));
    }

    public boolean requestShutdown() {
        if (!this.stopping.compareAndSet(false, true))
            return false;
        try {
            this.getServer().getScheduler().runTask(this, this.getServer()::shutdown);
            return true;
        } catch (RuntimeException error) {
            this.stopping.set(false);
            throw error;
        }
    }

    private void scheduleDrainShutdown(final Instant deadline) {
        final Runnable shutdown = () -> {
            if (this.stopping.compareAndSet(false, true))
                this.getServer().shutdown();
        };
        final long remainingMillis = Math.max(0L, Duration.between(this.clock.instant(), deadline).toMillis());
        final BukkitTask deadlineTask = this.getServer().getScheduler().runTaskLater(
                this, shutdown, (remainingMillis + 49L) / 50L);
        try {
            this.getServer().getScheduler().runTaskTimer(this, () -> {
                if (this.getServer().getOnlinePlayers().isEmpty() || !this.clock.instant().isBefore(deadline))
                    shutdown.run();
            }, 0L, DRAIN_CHECK_PERIOD_TICKS);
        } catch (RuntimeException error) {
            deadlineTask.cancel();
            throw error;
        }
    }

    @Override
    public void onDisable() {
        this.stopping.set(true);
        this.draining.set(true);
        if (this.echoClient != null && this.agonesLifecycle != null) {
            try {
                this.echoClient.setLocalServerAvailability(ServerAvailability.DRAINING).join();
            } catch (RuntimeException error) {
                getLogger().log(Level.WARNING, "Failed to drain Echo server during shutdown", error);
            }
        }
        try {
            if (this.echoClient != null)
                this.echoClient.shutdown();
        } finally {
            if (this.agonesLifecycle != null)
                this.agonesLifecycle.close();
        }
    }

}
