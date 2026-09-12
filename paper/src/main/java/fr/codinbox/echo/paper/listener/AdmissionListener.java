package fr.codinbox.echo.paper.listener;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import fr.codinbox.echo.api.server.ServerAdmissionSnapshot;
import fr.codinbox.echo.core.server.placement.RedisServerPlacement;
import fr.codinbox.echo.paper.EchoPaper;
import net.kyori.adventure.text.Component;
import io.papermc.paper.connection.PlayerConnection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/** One main-thread occupancy owner for every Paper ingress, including direct and initial joins. */
public final class AdmissionListener implements Listener, AutoCloseable {
    private final long admissionWaitNanos;
    private final EchoPaper plugin;
    private final RedisServerPlacement placement;
    private final String serverId;
    private final int publicCapacity;
    private final int hardCapacity;
    private final ConcurrentMap<PlayerConnection, UUID> connections = new ConcurrentHashMap<>();
    private final Map<UUID, Player> joining = new HashMap<>();
    private final ThreadPoolExecutor writer;

    public AdmissionListener(final @NotNull EchoPaper plugin, final @NotNull RedisServerPlacement placement,
                              final @NotNull String serverId, final int publicCapacity, final int hardCapacity) {
        this(plugin, placement, serverId, publicCapacity, hardCapacity, TimeUnit.MILLISECONDS.toNanos(100));
    }

    AdmissionListener(final EchoPaper plugin, final RedisServerPlacement placement, final String serverId,
                      final int publicCapacity, final int hardCapacity, final long admissionWaitNanos) {
        this.admissionWaitNanos = admissionWaitNanos;
        this.plugin = plugin;
        this.placement = placement;
        this.serverId = serverId;
        this.publicCapacity = publicCapacity;
        this.hardCapacity = hardCapacity;
        this.writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), Thread.ofPlatform().daemon().name("echo-admission-" + serverId).factory());
        this.writer.prestartCoreThread();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(final @NotNull AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED)
            this.connections.put(event.getConnection(), event.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(final @NotNull PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED)
            return;
        final Player player = event.getPlayer();
        try {
            if (this.plugin.isDraining() || this.joining.containsKey(player.getUniqueId())
                    || this.plugin.getServer().getPlayer(player.getUniqueId()) != null
                    || !this.admit(player, null)) {
                event.disallow(PlayerLoginEvent.Result.KICK_FULL, Component.text("This server has no available slot."));
                return;
            }
            this.joining.put(player.getUniqueId(), player);
        } catch (RuntimeException error) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, Component.text("Admission is unavailable. Please retry."));
            this.plugin.getLogger().log(Level.WARNING, "Failed to check Echo admission", error);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoginResult(final @NotNull PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED
                && this.removeJoining(event.getPlayer()))
            this.refresh();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(final @NotNull PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        try {
            // Recheck permission after configuration, before game listeners assign a role.
            if (this.plugin.isDraining() || !this.admit(player, player.getUniqueId()))
                player.kick(Component.text("This server has no available slot."));
        } catch (RuntimeException error) {
            player.kick(Component.text("Admission is unavailable. Please retry."));
            this.plugin.getLogger().log(Level.WARNING, "Failed to finalize Echo admission", error);
        }
        this.removeJoining(player);
        this.refresh();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final @NotNull PlayerQuitEvent event) {
        this.removeJoining(event.getPlayer());
        this.plugin.getServer().getScheduler().runTask(this.plugin, this::refresh);
    }

    @EventHandler
    public void onConnectionClose(final @NotNull PlayerConnectionCloseEvent event) {
        this.plugin.getServer().getScheduler().runTask(this.plugin, this::refresh);
    }

    private boolean removeJoining(final Player player) {
        // Bukkit Player equality is UUID-based; only the owning connection may release this seat.
        if (this.joining.get(player.getUniqueId()) != player)
            return false;
        this.joining.remove(player.getUniqueId());
        return true;
    }

    private boolean admit(final Player player, final UUID excluded) {
        final UUID member = player.getUniqueId();
        final boolean staff = player.hasPermission(ServerAdmissionSnapshot.STAFF_PERMISSION);
        final ServerAdmissionSnapshot snapshot = this.snapshot(excluded);
        final long deadline = System.nanoTime() + this.admissionWaitNanos;
        final Future<Boolean> result = this.writer.submit(() -> !this.writer.isShutdown() && System.nanoTime() < deadline
                && this.placement.admit(this.serverId, member, staff, snapshot));
        try {
            return result.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            result.cancel(false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Admission interrupted", interrupted);
        } catch (TimeoutException timeout) {
            // Do not interrupt an in-flight Redis transaction. A late write only holds a denied seat
            // conservatively until the next ordered snapshot replaces it; it never authorizes login.
            result.cancel(false);
            throw new IllegalStateException("Admission deadline exceeded", timeout);
        } catch (ExecutionException error) {
            throw new IllegalStateException("Admission failed", error.getCause());
        }
    }

    public void refresh() {
        try {
            // Close events identify only UUID, not connection. A rejected duplicate must not free
            // the original connection's pending seat while it is still configuring.
            this.connections.keySet().removeIf(connection -> !connection.isConnected());
            this.joining.keySet().removeIf(id -> !this.connections.containsValue(id));
            final ServerAdmissionSnapshot snapshot = this.snapshot(null);
            this.plugin.publishTelemetry(snapshot);
            try {
                this.writer.execute(() -> {
                    try {
                        if (!this.writer.isShutdown() && !snapshot.isStale(Instant.now()))
                            this.placement.publishAdmission(this.serverId, snapshot);
                    } catch (RuntimeException error) {
                        this.plugin.getLogger().log(Level.WARNING, "Failed to publish Echo admission", error);
                    }
                });
            } catch (RejectedExecutionException busy) {
                // One in-flight operation and two queued operations at most; retry on the next tick sample.
            }
        } catch (RuntimeException error) {
            this.plugin.getLogger().log(Level.WARNING, "Failed to publish Echo admission", error);
        }
    }

    @Override
    public void close() {
        this.writer.shutdown();
    }

    private ServerAdmissionSnapshot snapshot(final UUID excluded) {
        final Map<UUID, Boolean> online = new HashMap<>();
        this.plugin.getServer().getOnlinePlayers().forEach(player -> {
            if (!player.getUniqueId().equals(excluded))
                online.put(player.getUniqueId(), player.hasPermission(ServerAdmissionSnapshot.STAFF_PERMISSION));
        });
        final Map<UUID, Boolean> pending = new HashMap<>();
        this.joining.forEach((id, player) -> {
            if (!online.containsKey(id))
                pending.put(id, player.hasPermission(ServerAdmissionSnapshot.STAFF_PERMISSION));
        });
        final Instant now = Instant.now();
        return new ServerAdmissionSnapshot(online, pending, this.publicCapacity, this.hardCapacity,
                now, now.plusSeconds(5));
    }
}
