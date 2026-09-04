package fr.codinbox.echo.core;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.cache.CacheProviderFactory;
import fr.codinbox.echo.api.messaging.MessagingProviderFactory;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import fr.codinbox.echo.core.cache.RedisCacheProvider;
import fr.codinbox.echo.core.messaging.provider.RedisMessagingProvider;
import fr.codinbox.echo.core.server.placement.RedisServerPlacement;
import org.jetbrains.annotations.NotNull;

/**
 * Convenience factories for creating Redis-backed providers from a {@link RedisConnection}.
 */
public final class RedisProviderFactory {

    private RedisProviderFactory() {
    }

    /**
     * Creates a {@link CacheProviderFactory} backed by the given Redis connection.
     *
     * @param connection the Redis connection
     * @return a cache provider factory
     */
    public static @NotNull CacheProviderFactory cacheFactory(final @NotNull RedisConnection connection) {
        return () -> new RedisCacheProvider(connection);
    }

    /**
     * Creates a {@link MessagingProviderFactory} backed by the given Redis connection.
     *
     * @param connection the Redis connection
     * @return a messaging provider factory
     */
    public static @NotNull MessagingProviderFactory messagingFactory(final @NotNull RedisConnection connection) {
        return () -> new RedisMessagingProvider(connection);
    }

    /** Creates atomic leased server placement backed by the given Redis connection. */
    public static @NotNull ServerPlacement serverPlacement(final @NotNull RedisConnection connection) {
        return new RedisServerPlacement(connection);
    }

}
