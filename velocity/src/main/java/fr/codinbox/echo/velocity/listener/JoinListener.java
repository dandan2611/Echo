package fr.codinbox.echo.velocity.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.user.User;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class JoinListener {

    private final @NotNull BooleanSupplier acceptingLogins;
    private final @NotNull ConcurrentMap<UUID, String> userSessions;
    private final @NotNull Map<Player, UserSession> eventSessions =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final @NotNull Supplier<String> sessionIds;

    public JoinListener(@NotNull BooleanSupplier acceptingLogins) {
        this(acceptingLogins, new ConcurrentHashMap<>());
    }

    public JoinListener(@NotNull BooleanSupplier acceptingLogins, @NotNull ConcurrentMap<UUID, String> userSessions) {
        this(acceptingLogins, userSessions, () -> UUID.randomUUID().toString());
    }

    JoinListener(@NotNull BooleanSupplier acceptingLogins, @NotNull ConcurrentMap<UUID, String> userSessions,
                 @NotNull Supplier<String> sessionIds) {
        this.acceptingLogins = acceptingLogins;
        this.userSessions = userSessions;
        this.sessionIds = sessionIds;
    }

    @Subscribe(order = PostOrder.FIRST)
    private void onLogin(final @NotNull LoginEvent event) {
        if (!this.acceptingLogins.getAsBoolean()) {
            event.setResult(LoginEvent.ComponentResult.denied(
                    Component.text("This proxy is starting or draining. Please reconnect.")));
            return;
        }
        final Player player = event.getPlayer();
        final EchoClient client = Echo.getClient();

        final String currentResourceId = client.getCurrentResourceId().orElse(null);
        if (currentResourceId == null)
            return;

        final String sessionId = this.sessionIds.get();
        final CompletableFuture<User> created = client.createUser(
                player.getUniqueId(), player.getUsername(), currentResourceId, sessionId);
        this.eventSessions.put(player, new UserSession(sessionId, created));
        this.userSessions.put(player.getUniqueId(), sessionId);
    }

    @Subscribe(order = PostOrder.LAST)
    private void onDisconnect(final @NotNull DisconnectEvent event) {
        final Player player = event.getPlayer();
        final UserSession session = this.eventSessions.remove(player);
        if (session == null)
            return;
        this.userSessions.remove(player.getUniqueId(), session.id());
        final EchoClient client = Echo.getClient();

        session.created().thenCompose(user -> client.destroyUser(user, session.id()));
    }

    private record UserSession(String id, CompletableFuture<User> created) {
    }

}
