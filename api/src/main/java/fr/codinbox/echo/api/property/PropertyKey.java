package fr.codinbox.echo.api.property;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A type-safe key for storing and retrieving properties on a {@link PropertyHolder}.
 *
 * <p>Property keys are identified by their string key and carry a generic type parameter
 * to ensure type safety at compile time. Two {@code PropertyKey} instances are equal if
 * their string keys are equal, regardless of the type parameter.</p>
 *
 * <pre>{@code
 * // Define typed property keys
 * PropertyKey<Integer> LEVEL = new PropertyKey<>("level");
 * PropertyKey<String> RANK = new PropertyKey<>("rank");
 * PropertyKey<Boolean> VIP = PropertyKey.of("vip", Boolean.class);
 *
 * // Use with any PropertyHolder (User, Server, Proxy)
 * user.setProperty(LEVEL, 42).await();
 * Optional<Integer> level = user.getProperty(LEVEL).await();
 * }</pre>
 *
 * @param key the string key used to identify this property in Redis
 * @param <T> the type of the property value
 * @see PropertyHolder
 */
public record PropertyKey<T>(@NotNull String key) {

    /**
     * Creates a new {@code PropertyKey} with the given key and type.
     *
     * <p>The class parameter is used only for type inference and is not stored.</p>
     *
     * <pre>{@code
     * PropertyKey<Integer> key = PropertyKey.of("level", Integer.class);
     * }</pre>
     *
     * @param key   the string key
     * @param clazz the value type class (used for type inference only)
     * @param <T>   the value type
     * @return a new property key
     */
    public static <T> @NotNull PropertyKey<T> of(final @NotNull String key,
                                                 final @NotNull Class<T> clazz) {
        return new PropertyKey<T>(key);
    }

    @Override
    public @NotNull String toString() {
        return this.key;
    }

    /**
     * Two property keys are equal if their string keys are equal.
     */
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
