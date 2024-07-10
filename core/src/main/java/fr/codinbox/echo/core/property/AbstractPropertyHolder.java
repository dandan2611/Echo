package fr.codinbox.echo.core.property;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.property.PropertyHolder;
import fr.codinbox.echo.api.utils.Cleanable;
import fr.codinbox.echo.core.id.IdentifiableImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public abstract class AbstractPropertyHolder<ID> extends IdentifiableImpl<ID> implements PropertyHolder, Cleanable {

    public static final @NotNull String CREATION_TIME_KEY = "creation_time";

    private final @NotNull String keyPrefix;

    public AbstractPropertyHolder(final @NotNull ID id, final @NotNull String keyPrefix) {
        super(id);
        this.keyPrefix = keyPrefix;
    }

    private @NotNull String concat(final @NotNull String key) {
        return this.keyPrefix + ":property:" + key;
    }

    @Override
    public @NotNull CompletableFuture<Boolean> setExpire(@NotNull String key, final @NotNull Instant instant) {
        return Echo.getClient().getCacheProvider().expireObject(this.concat(key), instant);
    }

    @Override
    public @NotNull <T> CompletableFuture<T> getProperty(@NotNull String key) {
        return Echo.getClient().getCacheProvider().getObject(this.concat(key));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> deleteProperty(@NotNull String key) {
        return Echo.getClient().getCacheProvider().deleteObject(this.concat(key));
    }

    @Override
    public @NotNull <T> CompletableFuture<Void> setProperty(@NotNull String key, @NotNull T value) {
        return Echo.getClient().getCacheProvider().setObject(this.concat(key), value);
    }

    @Override
    public @NotNull CompletableFuture<Long> getPropertyTimeToLive(@NotNull String key) {
        return Echo.getClient().getCacheProvider().getObjectRemainingTimeToLive(this.concat(key));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> hasProperty(@NotNull String key) {
        return Echo.getClient().getCacheProvider().hasObject(this.concat(key));
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Set<String>> getProperties() {
        return Echo.getClient().getCacheProvider().getKeys(this.concat("*")).thenApply(keys -> keys.stream()
                .map(key -> key.replaceFirst(this.keyPrefix + ":property:", ""))
                .collect(Collectors.toSet()));
    }

    @Override
    public @NotNull CompletableFuture<@Nullable Long> getCreationTime() {
        return this.getProperty(CREATION_TIME_KEY);
    }

    @Override
    public @NotNull CompletableFuture<Void> cleanup() {
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);

        future = future.thenCombine(this.getProperties(), (aVoid, keys) -> {
            CompletableFuture<Boolean> deleteFuture = CompletableFuture.completedFuture(null);

            for (String key : keys) {
                deleteFuture = deleteFuture.thenCombine(this.deleteProperty(key), (aVoid1, aBoolean) -> null);
            }

            return deleteFuture.join();
        }).thenApply(aVoid -> null);

        return future;
    }

}
