package fr.codinbox.echo.api.utils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class for null-checking with custom exception types.
 */
public final class NullableUtils {

    /**
     * Ensures that a value is not {@code null}, throwing a custom exception if it is.
     *
     * <p>Unlike {@link java.util.Objects#requireNonNull(Object)}, this method lets you specify
     * the exception type to throw, which is useful for throwing domain-specific exceptions
     * like {@link fr.codinbox.echo.api.exception.resource.UnknownServerException}.</p>
     *
     * <pre>{@code
     * Server server = NullableUtils.requireNonNull(
     *     maybeServer,
     *     UnknownServerException.class,
     *     "lobby-1"
     * );
     * }</pre>
     *
     * @param type           the value to check
     * @param exceptionClazz the exception class to instantiate if the value is {@code null}
     * @param exceptionArgs  arguments to pass to the exception constructor
     * @param <T>            the value type
     * @param <E>            the exception type
     * @return the non-null value
     * @throws E if the value is {@code null}
     */
    public static <T, E extends Throwable> @NotNull T requireNonNull(final @Nullable T type,
                                                final @NotNull Class<E> exceptionClazz,
                                                final @NotNull Object... exceptionArgs) throws E {
        if (type == null) {
            try {
                throw exceptionClazz.getConstructor().newInstance(exceptionArgs);
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }
        return type;
    }

}
