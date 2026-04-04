package fr.codinbox.echo.api.cache;

import org.jetbrains.annotations.NotNull;

/**
 * Factory for creating {@link CacheProvider} instances.
 *
 * @see CacheProvider
 */
@FunctionalInterface
public interface CacheProviderFactory {

    /**
     * Creates a new cache provider instance.
     *
     * @return the cache provider
     */
    @NotNull CacheProvider create();

}
