package fr.codinbox.echo.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.connector.commons.redis.RedisConnectorService;
import fr.codinbox.connector.velocity.Connector;
import fr.codinbox.echo.agones.AgonesGameServerLifecycle;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoConfig;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.proxy.ProxyLoadSnapshot;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.MessageTarget;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.messaging.impl.ProxySwitchRequest;
import fr.codinbox.echo.api.messaging.impl.ResourceControlRequest;
import fr.codinbox.echo.api.messaging.impl.ServerAvailabilityNotification;
import fr.codinbox.echo.api.messaging.impl.ServerStatusNotification;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.api.messaging.impl.UserDisconnectRequest;
import fr.codinbox.echo.api.utils.EnvUtils;
import fr.codinbox.echo.commands.CommandAudience;
import fr.codinbox.echo.commands.EchoCommands;
import fr.codinbox.echo.core.EchoClientImpl;
import fr.codinbox.echo.core.RedisProviderFactory;
import fr.codinbox.echo.core.server.placement.RedisServerPlacement;
import fr.codinbox.echo.api.server.ServerAdmissionSnapshot;
import fr.codinbox.echo.velocity.listener.JoinListener;
import fr.codinbox.echo.velocity.listener.AdmissionPermissionListener;
import fr.codinbox.echo.velocity.messaging.ProxySwitchRequestHandler;
import fr.codinbox.echo.velocity.messaging.ResourceControlRequestHandler;
import fr.codinbox.echo.velocity.messaging.ServerAvailabilityNotificationHandler;
import fr.codinbox.echo.velocity.messaging.ServerStatusNotificationHandler;
import fr.codinbox.echo.velocity.messaging.ServerSwitchRequestHandler;
import fr.codinbox.echo.velocity.messaging.UserDisconnectRequestHandler;
import fr.codinbox.echo.velocity.utils.ProxyUtils;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.velocity.VelocityCommandManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

@Plugin(
        id = "echo",
        name = "Echo",
        version = "7.1.0",
        authors = {"dandan2611"},
        dependencies = {
                @Dependency(id = "connector", optional = false)
        }
)
public class EchoPlugin {

    public static final @NotNull String ECHO_CONNECTOR_CONNECTION_NAME = "ECHO";
    static final String COMMAND_ROOT = "echo|echoproxy";
    private static final Duration DRAIN_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration DRAIN_CHECK_PERIOD = Duration.ofSeconds(1);

    @Inject
    private Logger logger;

    @Inject
    private ProxyServer proxy;

    @Inject
    private PluginContainer pluginContainer;

    private EchoClient echoClient;
    private AgonesGameServerLifecycle agonesLifecycle;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean acceptingLogins = new AtomicBoolean();
    private final ConcurrentMap<UUID, String> userSessions = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    @Subscribe
    private void onProxyInit(final @NotNull ProxyInitializeEvent event) {
        try {
            final RedisConnectorService redisConnectorService = Connector.getRedisService();
            final Optional<RedisConnection> echoConnection = redisConnectorService.getConnection(ECHO_CONNECTOR_CONNECTION_NAME);

            if (echoConnection.isEmpty())
                throw new IllegalStateException("Failed to get Redis connection for Echo, is the " + ECHO_CONNECTOR_CONNECTION_NAME + " connection property configured?");

            final RedisConnection connection = echoConnection.get();
            final RedisServerPlacement placement = new RedisServerPlacement(connection);
            final boolean agonesEnabled = Boolean.parseBoolean(System.getenv("ECHO_AGONES_ENABLED"));
            if (agonesEnabled) {
                final String drainAnnotation = Optional.ofNullable(System.getenv("ECHO_AGONES_DRAIN_ANNOTATION"))
                        .orElse(AgonesGameServerLifecycle.DEFAULT_DRAIN_ANNOTATION);
                this.agonesLifecycle = AgonesGameServerLifecycle.inPod(
                        true, drainAnnotation, this::onAgonesDrain);
                final boolean drainRequested = this.agonesLifecycle.start()
                        .thenCompose(ignored -> this.agonesLifecycle.watchForDrainRequests())
                        .join();
                if (drainRequested || this.stopping.get())
                    return;
            }

            final EchoConfig config = EchoConfig.builder()
                    .cacheProviderFactory(RedisProviderFactory.cacheFactory(connection))
                    .messagingProviderFactory(RedisProviderFactory.messagingFactory(connection))
                     .serverPlacement(placement)
                     .resourceType(EchoResourceType.PROXY)
                     .resourceId(java.util.Objects.requireNonNull(EnvUtils.getResourceId()))
                     .initialProperties(EnvUtils.getInitialProperties())
                     .build();
            final EchoClient client = EchoClientImpl.autoInit(config);
            this.echoClient = client;
            this.proxy.getScheduler().buildTask(this, () -> {
                final Instant now = Instant.now();
                final Map<UUID, Boolean> permissions = new HashMap<>();
                this.proxy.getAllPlayers().forEach(player -> permissions.put(player.getUniqueId(),
                        player.hasPermission(ServerAdmissionSnapshot.STAFF_PERMISSION)));
                final int publicPlayers = (int) permissions.values().stream().filter(staff -> !staff).count();
                this.publishTelemetry(now, permissions.size(), publicPlayers);
                try {
                    placement.publishStaffPermissions(permissions);
                    final ProxyLoadSnapshot load =
                            new ProxyLoadSnapshot(permissions.size(),
                                    publicPlayers,
                                    ProxyLoadSnapshot.SCALE_OUT_THRESHOLD,
                                    now, now.plusSeconds(5));
                    client.getProxyById(config.getResourceId()).join().orElseThrow()
                            .setProperty(Proxy.PROPERTY_LOAD, load).join();
                } catch (RuntimeException error) {
                    this.logger.log(Level.WARNING, "Failed to publish admission permissions", error);
                }
            }).repeat(Duration.ofSeconds(1)).schedule();

            // Dynamic server registration
            final MessagingProvider messagingProvider = client.getMessagingProvider();
            messagingProvider.subscribe(MessageTarget.PROXIES_TOPIC, ServerStatusNotification.class, new ServerStatusNotificationHandler(this.logger, this.proxy));
            messagingProvider.subscribe(MessageTarget.PROXIES_TOPIC, ServerAvailabilityNotification.class,
                    new ServerAvailabilityNotificationHandler(this.logger, this.proxy, client));
            messagingProvider.subscribe(client.getLocalTopic(), ServerSwitchRequest.class, new ServerSwitchRequestHandler(this.logger, this.proxy));
            messagingProvider.subscribe(client.getLocalTopic(), ProxySwitchRequest.class, new ProxySwitchRequestHandler(this.logger, this.proxy));
            messagingProvider.subscribe(client.getLocalTopic(), ResourceControlRequest.class,
                    new ResourceControlRequestHandler(this, client));
            messagingProvider.subscribe(client.getLocalTopic(), UserDisconnectRequest.class,
                    new UserDisconnectRequestHandler(this, client));

            final VelocityCommandManager<CommandSource> commandManager = new VelocityCommandManager<>(
                    this.pluginContainer, this.proxy, ExecutionCoordinator.simpleCoordinator(), SenderMapper.identity());
            final AnnotationParser<CommandSource> commandParser =
                    new AnnotationParser<>(commandManager, CommandSource.class);
            new EchoCommands<>(client, commandAudience(), COMMAND_ROOT).register(commandParser);

            // Load existing servers
            client.getServers().thenAccept(servers -> {
                for (String s : servers.keySet()) {
                    client.getServerById(s).thenAccept(serverOpt -> {
                        serverOpt.ifPresent(server -> {
                            ProxyUtils.registerServerIfActive(this.proxy, this.logger, server);
                        });
                    });
                }
            });

            // Register listeners
            final EventManager eventManager = this.proxy.getEventManager();
            eventManager.register(this, new AdmissionPermissionListener(placement));
            eventManager.register(this, new JoinListener(() -> this.acceptingLogins.get()
                    && !this.stopping.get() && !this.draining.get(), this.userSessions));

            if (!this.stopping.get() && !this.draining.get()) {
                this.acceptingLogins.set(true);
                if (this.stopping.get() || this.draining.get())
                    this.acceptingLogins.set(false);
            }

        } catch (Exception e) {
            this.logger.log(Level.SEVERE, "Failed to initialize Echo client", e);
            this.proxy.shutdown();
            return;
        }
    }

    static CommandAudience<CommandSource> commandAudience() {
        return new CommandAudience<>() {
            @Override
            public void send(CommandSource source, Component message) {
                source.sendMessage(message);
            }

            @Override
            public String identity(CommandSource source) {
                return source instanceof Player player ? player.getUsername() : "CONSOLE";
            }
        };
    }

    void publishTelemetry(final @NotNull Instant sampledAt, final int total, final int publicPlayers) {
        if (this.agonesLifecycle != null)
            this.agonesLifecycle.publishTelemetry(sampledAt, total, publicPlayers,
                    ProxyLoadSnapshot.SCALE_OUT_THRESHOLD).exceptionally(error -> {
                this.logger.log(Level.WARNING, "Failed to publish Agones telemetry", error);
                return null;
            });
    }

    public boolean beginDrain() {
        return this.beginDrain(this.clock.instant().plus(DRAIN_TIMEOUT));
    }

    CompletableFuture<Void> onAgonesDrain() {
        this.beginDrain();
        return CompletableFuture.completedFuture(null);
    }

    public boolean beginDrain(final @NotNull Instant deadline) {
        java.util.Objects.requireNonNull(deadline, "deadline");
        if (this.stopping.get() || !this.draining.compareAndSet(false, true))
            return false;
        this.acceptingLogins.set(false);
        try {
            this.scheduleDrainShutdown(deadline);
            return true;
        } catch (RuntimeException error) {
            this.draining.set(false);
            if (!this.stopping.get())
                this.acceptingLogins.set(true);
            throw error;
        }
    }

    public boolean activate() {
        if (this.stopping.get() || this.draining.get())
            return false;
        this.acceptingLogins.set(true);
        if (this.stopping.get() || this.draining.get()) {
            this.acceptingLogins.set(false);
            return false;
        }
        return true;
    }

    public boolean requestShutdown() {
        if (!this.stopping.compareAndSet(false, true))
            return false;
        this.acceptingLogins.set(false);
        try {
            this.proxy.getScheduler().buildTask(this, () -> this.proxy.shutdown()).schedule();
            return true;
        } catch (RuntimeException error) {
            this.stopping.set(false);
            if (!this.draining.get())
                this.acceptingLogins.set(true);
            throw error;
        }
    }

    public CompletableFuture<Boolean> disconnectPlayer(@NotNull UUID userId, @NotNull String reason) {
        return this.scheduleDisconnect(userId, reason, null, Long.MAX_VALUE);
    }

    public CompletableFuture<Boolean> disconnectPlayer(@NotNull UUID userId, @NotNull String reason,
                                                        @NotNull String expectedSessionId) {
        java.util.Objects.requireNonNull(expectedSessionId, "expectedSessionId");
        return this.scheduleDisconnect(userId, reason, expectedSessionId, Long.MAX_VALUE);
    }

    public CompletableFuture<Boolean> disconnectPlayer(@NotNull UUID userId, @NotNull String reason,
                                                        @NotNull String expectedSessionId,
                                                        long deadlineEpochMillis) {
        java.util.Objects.requireNonNull(expectedSessionId, "expectedSessionId");
        return this.scheduleDisconnect(userId, reason, expectedSessionId, deadlineEpochMillis);
    }

    private CompletableFuture<Boolean> scheduleDisconnect(UUID userId, String reason, String expectedSessionId,
                                                           long deadlineEpochMillis) {
        java.util.Objects.requireNonNull(userId, "userId");
        java.util.Objects.requireNonNull(reason, "reason");
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            this.proxy.getScheduler().buildTask(this, () -> {
                try {
                    if (this.clock.millis() >= deadlineEpochMillis) {
                        result.completeExceptionally(new TimeoutException("Disconnect deadline elapsed"));
                        return;
                    }
                    Optional<Player> player = this.proxy.getPlayer(userId);
                    if (expectedSessionId != null
                            && (player.isEmpty() || !this.userSessions.remove(userId, expectedSessionId))) {
                        result.complete(false);
                        return;
                    }
                    player.ifPresent(value -> value.disconnect(Component.text(reason)));
                    result.complete(player.isPresent());
                } catch (RuntimeException error) {
                    result.completeExceptionally(error);
                }
            }).schedule();
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
        }
        return result;
    }

    private void scheduleDrainShutdown(Instant deadline) {
        final Runnable shutdown = () -> {
            if (this.stopping.compareAndSet(false, true)) {
                this.acceptingLogins.set(false);
                this.proxy.shutdown();
            }
        };
        final ScheduledTask deadlineTask = this.proxy.getScheduler().buildTask(this, shutdown)
                .delay(Duration.ofMillis(Math.max(0L, Duration.between(this.clock.instant(), deadline).toMillis())))
                .schedule();
        try {
            this.proxy.getScheduler().buildTask(this, task -> {
                if (this.stopping.get()) {
                    task.cancel();
                } else if (this.proxy.getPlayerCount() == 0 || !this.clock.instant().isBefore(deadline)) {
                    task.cancel();
                    shutdown.run();
                }
            }).repeat(DRAIN_CHECK_PERIOD).schedule();
        } catch (RuntimeException error) {
            deadlineTask.cancel();
            throw error;
        }
    }

    @Subscribe(order = PostOrder.LATE, async = false)
    private void onProxyShutdown(final @NotNull ProxyShutdownEvent event) {
        this.stopping.set(true);
        this.draining.set(true);
        this.acceptingLogins.set(false);
        try {
            if (this.echoClient != null)
                this.echoClient.shutdown();
        } finally {
            if (this.agonesLifecycle != null)
                this.agonesLifecycle.close();
        }
    }

}
