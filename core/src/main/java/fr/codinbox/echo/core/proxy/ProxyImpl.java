package fr.codinbox.echo.core.proxy;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessageTarget;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.core.property.AbstractPropertyHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RMapAsync;

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
    public @NotNull CompletableFuture<Void> sendMessage(@NotNull EchoMessage message) {
        return Echo.getClient().getMessagingProvider()
                .publish(PROXY_TOPIC.formatted(this.getId()), message);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Map<String, Instant>> getConnectedUsers() {
        return this.getConnectedUsersMap().readAllMapAsync().toCompletableFuture();
    }

    private @NotNull RMapAsync<String, Instant> getConnectedUsersMap() {
        return Echo.getClient().getCacheProvider().getMap(USER_MAP_KEY.formatted(this.getId()));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> hasUser(@NotNull UUID id) {
        return this.getConnectedUsers().thenApply(map -> map.containsKey(id.toString()));
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

}
