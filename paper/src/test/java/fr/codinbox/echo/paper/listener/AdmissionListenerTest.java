package fr.codinbox.echo.paper.listener;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.server.ServerAdmissionSnapshot;
import fr.codinbox.echo.core.server.placement.RedisServerPlacement;
import fr.codinbox.echo.paper.EchoPaper;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import io.papermc.paper.connection.PlayerLoginConnection;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@Tag("unit")
class AdmissionListenerTest {
    private final List<CountDownLatch> blockedCommands = new ArrayList<>();
    private final List<AdmissionListener> listeners = new ArrayList<>();

    @AfterEach
    void releaseCommands() {
        blockedCommands.forEach(CountDownLatch::countDown);
        listeners.forEach(AdmissionListener::close);
    }

    @Test
    void redisCommandStallDoesNotBlockTheRefreshTick() throws Exception {
        final Fixture fixture = new Fixture(0, 0, TimeUnit.MILLISECONDS.toNanos(100));
        final CountDownLatch entered = blockRedis(fixture);

        final CompletableFuture<Void> tick = CompletableFuture.runAsync(fixture.listener::refresh);

        tick.get(500, TimeUnit.MILLISECONDS);
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void closeDoesNotWaitForBlockedRedis() throws Exception {
        final Fixture fixture = new Fixture(0, 0);
        final CountDownLatch entered = blockRedis(fixture);
        fixture.listener.refresh();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

        CompletableFuture.runAsync(fixture.listener::close).get(500, TimeUnit.MILLISECONDS);
    }

    @Test
    void redisCommandStallDeniesLoginWithinTheAdmissionWaitBudget() throws Exception {
        final Fixture fixture = new Fixture(0, 0, TimeUnit.MILLISECONDS.toNanos(100));
        final CountDownLatch entered = blockRedis(fixture);
        final PlayerLoginEvent login = fixture.login(true);

        final CompletableFuture<Void> tick = CompletableFuture.runAsync(() -> fixture.listener.onLogin(login));

        tick.get(500, TimeUnit.MILLISECONDS);
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(login.getResult()).isEqualTo(PlayerLoginEvent.Result.KICK_OTHER);
    }

    @Test
    void permissionsStayOnTheTickThreadWhileRedisRunsOffThread() {
        final Fixture fixture = new Fixture(0, 0);
        final PlayerLoginEvent login = fixture.login(true);
        final AtomicReference<Thread> permissionThread = new AtomicReference<>();
        final AtomicReference<Thread> redisThread = new AtomicReference<>();
        when(login.getPlayer().hasPermission(ServerAdmissionSnapshot.STAFF_PERMISSION)).thenAnswer(ignored -> {
            permissionThread.set(Thread.currentThread());
            return true;
        });
        when(fixture.redis.getBucket(anyString())).thenAnswer(invocation -> {
            redisThread.set(Thread.currentThread());
            return fixture.bucket(invocation.getArgument(0));
        });

        fixture.listener.onLogin(login);

        assertThat(login.getResult()).isEqualTo(PlayerLoginEvent.Result.ALLOWED);
        assertThat(permissionThread.get()).isSameAs(Thread.currentThread());
        assertThat(redisThread.get()).isNotNull().isNotSameAs(Thread.currentThread());
    }

    @Test
    void lateUnlockCannotAuthorizeLoginOrOverwriteTheFollowingSnapshot() throws Exception {
        final Fixture fixture = new Fixture(0, 0, TimeUnit.MILLISECONDS.toNanos(100));
        final PlayerLoginEvent login = fixture.login(true);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(2);
        blockedCommands.add(release);
        doAnswer(ignored -> {
            entered.countDown();
            release.await();
            finished.countDown();
            return null;
        }).when(fixture.lock).unlock();

        fixture.listener.onLogin(login);
        fixture.listener.refresh();
        release.countDown();

        assertThat(entered.await(1, TimeUnit.SECONDS) && finished.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(login.getResult()).isEqualTo(PlayerLoginEvent.Result.KICK_OTHER);
        assertThat(((ServerAdmissionSnapshot) fixture.values.get("server:server:property:admission"))
                .joiningMembers()).isEmpty();
    }

    private CountDownLatch blockRedis(final Fixture fixture) {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        blockedCommands.add(release);
        when(fixture.redis.getBucket(anyString())).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            throw new IllegalStateException("Redis unavailable");
        });
        return entered;
    }

    @Test
    void rejectedDuplicateWithBukkitUuidEqualityCannotReleaseOriginalSeat() {
        final Fixture fixture = new Fixture(119, 100);
        final UUID id = UUID.randomUUID();
        final PlayerLoginEvent original = new PlayerLoginEvent(uuidPlayer(id), "localhost", InetAddress.getLoopbackAddress());
        final PlayerLoginEvent duplicate = new PlayerLoginEvent(uuidPlayer(id), "localhost", InetAddress.getLoopbackAddress());
        final PlayerLoginEvent replacement = fixture.login(true);
        fixture.listener.onLogin(original);
        fixture.listener.onLogin(duplicate);
        fixture.listener.onLoginResult(duplicate);
        fixture.listener.onLogin(replacement);

        assertThat(replacement.getResult()).isEqualTo(PlayerLoginEvent.Result.KICK_FULL);
    }

    private static Player uuidPlayer(final UUID id) {
        return (Player) java.lang.reflect.Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "hasPermission" -> true;
                    case "equals" -> arguments[0] instanceof Player other && id.equals(other.getUniqueId());
                    case "hashCode" -> id.hashCode();
                    default -> null;
                });
    }

    @Test
    void sdkSampleIsStillPublishedWhenRedisFails() {
        final Fixture fixture = new Fixture(0, 0);
        when(fixture.redis.getBucket(anyString())).thenThrow(new IllegalStateException("Redis unavailable"));

        fixture.listener.refresh();

        verify(fixture.plugin).publishTelemetry(any(ServerAdmissionSnapshot.class));
    }

    @Test
    void directNonStaffLoginIsRejectedAtPublicLimit() {
        final Fixture fixture = new Fixture(100, 100);
        final PlayerLoginEvent event = fixture.login(false);

        fixture.listener.onLogin(event);

        assertThat(event.getResult()).isEqualTo(PlayerLoginEvent.Result.KICK_FULL);
    }

    @Test
    void directStaffLoginUsesSurplusButStillHoldsAPendingSeat() {
        final Fixture fixture = new Fixture(119, 100);
        final PlayerLoginEvent first = fixture.login(true);
        final PlayerLoginEvent second = fixture.login(true);

        fixture.listener.onLogin(first);
        fixture.listener.onLogin(second);

        assertThat(first.getResult()).isEqualTo(PlayerLoginEvent.Result.ALLOWED);
        assertThat(second.getResult()).isEqualTo(PlayerLoginEvent.Result.KICK_FULL);
    }

    @Test
    void cancelledLoginReleasesPendingSeat() {
        final Fixture fixture = new Fixture(119, 100);
        final PlayerLoginEvent cancelled = fixture.login(true);
        fixture.listener.onLogin(cancelled);
        cancelled.setResult(PlayerLoginEvent.Result.KICK_OTHER);
        fixture.listener.onLoginResult(cancelled);
        final PlayerLoginEvent replacement = fixture.login(true);

        fixture.listener.onLogin(replacement);

        assertThat(replacement.getResult()).isEqualTo(PlayerLoginEvent.Result.ALLOWED);
    }

    @Test
    void duplicateCloseCannotReleaseLiveConfigurationConnection() {
        final Fixture fixture = new Fixture(119, 100);
        final PlayerLoginEvent pending = fixture.login(true);
        fixture.listener.onLogin(pending);
        fixture.liveConnection(pending.getPlayer().getUniqueId());
        final PlayerLoginEvent replacement = fixture.login(true);

        fixture.listener.onConnectionClose(new PlayerConnectionCloseEvent(pending.getPlayer().getUniqueId(),
                "player", InetAddress.getLoopbackAddress(), false));
        fixture.listener.onLogin(replacement);

        assertThat(replacement.getResult()).isEqualTo(PlayerLoginEvent.Result.KICK_FULL);
    }

    @Test
    void disconnectedConfigurationConnectionReleasesItsSeat() {
        final Fixture fixture = new Fixture(119, 100);
        final PlayerLoginEvent pending = fixture.login(true);
        fixture.listener.onLogin(pending);
        final PlayerLoginEvent replacement = fixture.login(true);

        fixture.listener.onConnectionClose(new PlayerConnectionCloseEvent(pending.getPlayer().getUniqueId(),
                "player", InetAddress.getLoopbackAddress(), false));
        fixture.listener.onLogin(replacement);

        assertThat(replacement.getResult()).isEqualTo(PlayerLoginEvent.Result.ALLOWED);
    }

    @Test
    void redisFailureDeniesInsteadOfBypassingAdmission() {
        final Fixture fixture = new Fixture(0, 0);
        when(fixture.redis.getBucket(anyString())).thenThrow(new IllegalStateException("Redis unavailable"));
        final PlayerLoginEvent event = fixture.login(true);

        fixture.listener.onLogin(event);

        assertThat(event.getResult()).isEqualTo(PlayerLoginEvent.Result.KICK_OTHER);
    }

    @Test
    void drainStartingDuringConfigurationRejectsArrival() {
        final Fixture fixture = new Fixture(0, 0);
        final PlayerLoginEvent login = fixture.login(true);
        fixture.listener.onLogin(login);
        when(fixture.plugin.isDraining()).thenReturn(true);

        fixture.listener.onJoin(new PlayerJoinEvent(login.getPlayer(), Component.empty()));

        verify(login.getPlayer()).kick(any(Component.class));
    }

    private final class Fixture {
        private final RedissonClient redis = mock(RedissonClient.class);
        private final RLock lock = mock(RLock.class);
        private final EchoPaper plugin = mock(EchoPaper.class);
        private final Server server = mock(Server.class);
        private final Map<String, Object> values = new ConcurrentHashMap<>();
        private final AdmissionListener listener;

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Fixture(final int total, final int nonStaff) {
            this(total, nonStaff, TimeUnit.SECONDS.toNanos(1));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Fixture(final int total, final int nonStaff, final long waitNanos) {
            final RedisConnection connection = mock(RedisConnection.class);
            when(connection.getClient()).thenReturn(redis);
            when(redis.getLock(anyString())).thenReturn(lock);
            try {
                when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
            } catch (InterruptedException impossible) {
                throw new AssertionError(impossible);
            }
            when(redis.getMapCache(anyString())).thenReturn(mock(RMapCache.class));
            when(redis.getBucket(anyString())).thenAnswer(invocation -> bucket(invocation.getArgument(0)));
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getLogger()).thenReturn(mock(Logger.class));
            final List<Player> online = java.util.stream.IntStream.range(0, total)
                    .mapToObj(index -> player(index >= nonStaff)).toList();
            when(server.getOnlinePlayers()).thenAnswer(ignored -> online);
            final BukkitScheduler scheduler = mock(BukkitScheduler.class);
            when(server.getScheduler()).thenReturn(scheduler);
            doAnswer(invocation -> {
                invocation.<Runnable>getArgument(1).run();
                return null;
            }).when(scheduler).runTask(any(), any(Runnable.class));
            listener = new AdmissionListener(plugin, new RedisServerPlacement(connection), "server", 100, 120, waitNanos);
            listeners.add(listener);
        }

        private void liveConnection(final UUID id) {
            final PlayerLoginConnection connection = mock(PlayerLoginConnection.class);
            final AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
            when(connection.isConnected()).thenReturn(true);
            when(event.getConnection()).thenReturn(connection);
            when(event.getUniqueId()).thenReturn(id);
            when(event.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.ALLOWED);
            listener.onPreLogin(event);
        }

        private RBucket<Object> bucket(final String key) {
            final RBucket<Object> bucket = mock(RBucket.class);
            when(bucket.get()).thenAnswer(ignored -> values.get(key));
            doAnswer(invocation -> {
                values.put(key, invocation.getArgument(0));
                return null;
            }).when(bucket).set(any());
            return bucket;
        }

        private PlayerLoginEvent login(final boolean staff) {
            return new PlayerLoginEvent(player(staff), "localhost", InetAddress.getLoopbackAddress());
        }

        private static Player player(final boolean staff) {
            final Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.hasPermission(ServerAdmissionSnapshot.STAFF_PERMISSION)).thenReturn(staff);
            return player;
        }

    }
}
