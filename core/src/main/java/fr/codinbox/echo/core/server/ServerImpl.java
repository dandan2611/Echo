package fr.codinbox.echo.core.server;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessageTarget;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.core.property.AbstractPropertyHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RMapAsync;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ServerImpl extends AbstractPropertyHolder<String> implements Server {

    public static final @NotNull String SERVER_MAP = "servers:map";
    public static final @NotNull String SERVER_TOPIC = "server:%s";
    public static final @NotNull String USER_MAP_KEY = "server:%s:users";
    public static final @NotNull String SERVER_KEY = "server:%s";
    public static final @NotNull String SERVER_ADDRESS_KEY = "server:%s:address";

    public ServerImpl(final @NotNull String id, final @Nullable Address address) {
        super(id, SERVER_KEY.formatted(id));

        if (address != null)
            Echo.getClient().getCacheProvider().setObject(SERVER_ADDRESS_KEY.formatted(id), address);
    }

    @Override
    public @NotNull Address getAddress() {
        return Objects.requireNonNull(Echo.getClient().getCacheProvider().getObjectSync(SERVER_ADDRESS_KEY.formatted(this.getId())));
    }

    @Override
    public @NotNull CompletableFuture<Void> publishMessage(final @NotNull MessageTarget target,
                                                            final @NotNull EchoMessage message) {
        return Echo.getClient().getMessagingProvider().publishAll(target.getTargets(), message);
    }

    @Override
    public @NotNull CompletableFuture<Void> sendMessage(@NotNull EchoMessage message) {
        return Echo.getClient().getMessagingProvider()
                .publish(SERVER_TOPIC.formatted(this.getId()), message);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Map<String, Instant>> getConnectedUsers() {
        return this.getConnectedUsersMap().readAllMapAsync().toCompletableFuture();
    }

    private @NotNull RMapAsync<String, Instant> getConnectedUsersMap() {
        return Echo.getClient().getCacheProvider().getMap(USER_MAP_KEY.formatted(this.getId()));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> hasUser(final @NotNull UUID id) {
        return this.getConnectedUsers().thenApply(map -> map.containsKey(id.toString()));
    }

    @Override
    public @NotNull CompletableFuture<Void> cleanup() {
        return Echo.getClient().getCacheProvider().deleteObject(SERVER_ADDRESS_KEY.formatted(this.getId()))
                .thenCombine(super.cleanup(), (a, b) -> null);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Boolean> stillExists() {
        return Echo.getClient().getCacheProvider().hasObject(SERVER_ADDRESS_KEY.formatted(this.getId()));
    }

    @Override
    public @NotNull CompletableFuture<Void> registerUser(@NotNull User user) {
        return this.getConnectedUsersMap().fastPutAsync(user.getId().toString(), Instant.now())
                .toCompletableFuture()
                .thenApply(aVoid -> null);
    }

    @Override
    public @NotNull CompletableFuture<Void> unregisterUser(@NotNull User user) {
        return this.getConnectedUsersMap().fastRemoveAsync(user.getId().toString())
                .toCompletableFuture()
                .thenApply(aVoid -> null);
    }

}
