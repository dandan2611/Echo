package fr.codinbox.echo.core.server;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessageTarget;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.core.property.AbstractPropertyHolder;
import fr.codinbox.echo.core.utils.MapUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RMap;
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
    public @NotNull CompletableFuture<@NotNull Map<UUID, Instant>> getConnectedUsersAsync() {
        return this.getConnectedUsersMap().readAllMapAsync().toCompletableFuture()
                .thenApplyAsync(MapUtils::mapStringToUuidKey);
    }

    private @NotNull RMap<String, Instant> getConnectedUsersMap() {
        return Echo.getClient().getCacheProvider().getMap(USER_MAP_KEY.formatted(this.getId()));
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Boolean> hasUserAsync(final @NotNull UUID id) {
        return this.getConnectedUsersMap().containsKeyAsync(id.toString()).toCompletableFuture();
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
    public @NotNull CompletableFuture<@NotNull Boolean> registerUserAsync(final @NotNull User user) {
        return this.getConnectedUsersMap().fastPutAsync(user.getId().toString(), Instant.now())
                .toCompletableFuture();
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Boolean> unregisterUserAsync(final @NotNull User user) {
        return this.getConnectedUsersMap().fastRemoveAsync(user.getId().toString())
                .toCompletableFuture()
                .thenApply(l -> l >= 1);
    }

}
