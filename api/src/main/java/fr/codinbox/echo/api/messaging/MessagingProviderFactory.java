package fr.codinbox.echo.api.messaging;

import org.jetbrains.annotations.NotNull;

/**
 * Factory for creating {@link MessagingProvider} instances.
 *
 * @see MessagingProvider
 */
@FunctionalInterface
public interface MessagingProviderFactory {

    /**
     * Creates a new messaging provider instance.
     *
     * @return the messaging provider
     */
    @NotNull MessagingProvider create();

}
