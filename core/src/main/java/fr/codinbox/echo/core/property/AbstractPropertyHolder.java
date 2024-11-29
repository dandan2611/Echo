package fr.codinbox.echo.core.property;

import fr.codinbox.echo.api.Echo;
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
    public @NotNull CompletableFuture<Boolean> setExpireAsync(final @NotNull String key,
                                                              final @NotNull Instant instant) {
        return Echo.getClient().getCacheProvider().expireObject(this.concat(key), instant);
    }

    @Override
    public @NotNull <T> CompletableFuture<@NotNull Optional<T>> getPropertyAsync(final @NotNull String key) {
        return Echo.getClient().getCacheProvider().<T>getObject(this.concat(key)).thenApplyAsync(Optional::ofNullable);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Boolean> deletePropertyAsync(final @NotNull String key) {
        return Echo.getClient().getCacheProvider().deleteObject(this.concat(key));
    }

    @Override
    public @NotNull <T> CompletableFuture<Void> setPropertyAsync(final @NotNull String key, final @Nullable T value) {
        if (value == null)
            return this.deletePropertyAsync(key).thenApply(aBoolean -> null);
        return Echo.getClient().getCacheProvider().setObject(this.concat(key), value);
    }

    @Override
    public @NotNull CompletableFuture<Long> getPropertyTimeToLiveAsync(final @NotNull String key) {
        return Echo.getClient().getCacheProvider().getObjectRemainingTimeToLive(this.concat(key));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> hasPropertyAsync(final @NotNull String key) {
        return Echo.getClient().getCacheProvider().hasObject(this.concat(key));
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Set<String>> getPropertiesKeysAsync() {
        return Echo.getClient().getCacheProvider().getKeys(this.concat("*")).thenApply(keys -> keys.stream()
                .map(key -> key.replaceFirst(this.keyPrefix + ":property:", ""))
                .collect(Collectors.toSet()));
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Optional<Long>> getCreationTimeAsync() {
        return this.getPropertyAsync(CREATION_TIME_KEY);
    }

    @Override
    public @NotNull CompletableFuture<Void> cleanup() {
        return this.getPropertiesKeysAsync().thenComposeAsync(properties -> {
            return CompletableFuture.allOf(
                    properties.stream()
                            .map(this::deletePropertyAsync)
                            .toArray(CompletableFuture[]::new)
            );
        });
    }

}
