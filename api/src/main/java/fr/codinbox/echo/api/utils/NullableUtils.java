package fr.codinbox.echo.api.utils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class NullableUtils {

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
