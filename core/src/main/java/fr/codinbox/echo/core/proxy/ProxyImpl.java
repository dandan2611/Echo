package fr.codinbox.echo.core.proxy;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessageTarget;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.core.property.AbstractPropertyHolder;
import fr.codinbox.echo.core.utils.MapUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RMap;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ProxyImpl extends AbstractPropertyHolder<String> implements Proxy {

    public static final @NotNull String PROXY_MAP = "proxies:map";
    public static final @NotNull String PROXY_TOPIC = "proxy:%s";
    public static final @NotNull String USER_MAP_KEY = "server:%s:users";
    public static final @NotNull String PROXY_KEY = "proxy:%s";
    public static final @NotNull String PROXY_ADDRESS_KEY = "proxy:%s:address";

    public ProxyImpl(final @NotNull String id, final @Nullable Address address) {
        super(id, PROXY_KEY.formatted(id));

        if (address != null)
            Echo.getClient().getCacheProvider().setObject(PROXY_ADDRESS_KEY.formatted(id), address);
    }

    @Override
    public @NotNull CompletableFuture<Void> publishMessage(final @NotNull MessageTarget target,
                                                            final @NotNull EchoMessage message) {
        return Echo.getClient().getMessagingProvider().publishAll(target.getTargets(), message);
    }

    @Override
    public @NotNull CompletableFuture<Void> sendMessage(final @NotNull EchoMessage message) {
        return Echo.getClient().getMessagingProvider()
                .publish(PROXY_TOPIC.formatted(this.getId()), message);
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
    public @NotNull Address getAddress() {
        return Objects.requireNonNull(Echo.getClient().getCacheProvider().getObjectSync(PROXY_ADDRESS_KEY.formatted(this.getId())));
    }

    @Override
    public @NotNull CompletableFuture<Void> cleanup() {
        return Echo.getClient().getCacheProvider().deleteObject(PROXY_ADDRESS_KEY.formatted(this.getId()))
                .thenCombine(super.cleanup(), (a, b) -> null);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Boolean> stillExists() {
        return CompletableFuture.completedFuture(true);
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

    @Override
    public @NotNull CompletableFuture<@NotNull Boolean> clearUsersAsync() {
        return this.getConnectedUsersMap().clearAsync().toCompletableFuture();
    }

}
