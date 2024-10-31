package fr.codinbox.echo.api.property;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record PropertyKey<T>(@NotNull String key) {

    public static <T> @NotNull PropertyKey<T> of(final @NotNull String key,
                                                 final @NotNull Class<T> clazz) {
        return new PropertyKey<T>(key);
    }

    @Override
    public @NotNull String toString() {
        return this.key;
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        final PropertyKey<?> that = (PropertyKey<?>) obj;
        return this.key.equals(that.key);
    }
}
