package fr.codinbox.echo.core.property;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.property.PropertyHolder;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.utils.Cleanable;
import fr.codinbox.echo.core.id.IdentifiableImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public abstract class AbstractPropertyHolder<ID> extends IdentifiableImpl<ID> implements PropertyHolder, Cleanable {

    public static final @NotNull PropertyKey<Long> CREATION_TIME_KEY = new PropertyKey<>("creation_time");

    private final @NotNull String keyPrefix;

    public AbstractPropertyHolder(final @NotNull ID id, final @NotNull String keyPrefix) {
        super(id);
        this.keyPrefix = keyPrefix;
    }

    private @NotNull String concat(final @NotNull String key) {
        return this.keyPrefix + ":property:" + key;
    }

    @Override
    public @NotNull EchoFuture<Boolean> setExpire(final @NotNull String key,
                                                   final @NotNull Instant instant) {
        return EchoFuture.of(Echo.getClient().getCacheProvider().expireObject(this.concat(key), instant));
    }

    @Override
    public @NotNull <T> EchoFuture<@NotNull Optional<T>> getProperty(final @NotNull String key) {
        return EchoFuture.of(Echo.getClient().getCacheProvider().<T>getObject(this.concat(key)).thenApplyAsync(Optional::ofNullable));
    }

    @Override
    public @NotNull EchoFuture<@NotNull Boolean> deleteProperty(final @NotNull String key) {
        return EchoFuture.of(Echo.getClient().getCacheProvider().deleteObject(this.concat(key)));
    }

    @Override
    public @NotNull <T> EchoFuture<Void> setProperty(final @NotNull String key, final @Nullable T value) {
        if (value == null)
            return EchoFuture.of(this.deleteProperty(key).thenApply(aBoolean -> null));
        return EchoFuture.of(Echo.getClient().getCacheProvider().setObject(this.concat(key), value));
    }

    @Override
    public @NotNull EchoFuture<@NotNull Long> getPropertyTimeToLive(final @NotNull String key) {
        return EchoFuture.of(Echo.getClient().getCacheProvider().getObjectRemainingTimeToLive(this.concat(key)));
    }

    @Override
    public @NotNull EchoFuture<@NotNull Boolean> hasProperty(final @NotNull String key) {
        return EchoFuture.of(Echo.getClient().getCacheProvider().hasObject(this.concat(key)));
    }

    @Override
    public @NotNull EchoFuture<@NotNull Set<String>> getPropertiesKeys() {
        return EchoFuture.of(Echo.getClient().getCacheProvider().getKeys(this.concat("*")).thenApply(keys -> keys.stream()
                .map(key -> key.replaceFirst(this.keyPrefix + ":property:", ""))
                .collect(Collectors.toSet())));
    }

    @Override
    public @NotNull EchoFuture<@NotNull Optional<Long>> getCreationTime() {
        return EchoFuture.of(this.getProperty(CREATION_TIME_KEY));
    }

    @Override
    public @NotNull EchoFuture<Void> cleanup() {
        return EchoFuture.of(this.getPropertiesKeys().thenComposeAsync(properties -> {
            return CompletableFuture.allOf(
                    properties.stream()
                            .map(this::deleteProperty)
                            .toArray(CompletableFuture[]::new)
            );
        }));
    }

}
