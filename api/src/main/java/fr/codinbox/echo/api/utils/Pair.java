package fr.codinbox.echo.api.utils;

/**
 * A simple pair of two values.
 *
 * @param first the first value
 * @param second the second value
 * @param <T> the type of the first value
 * @param <U> the type of the second value
 */
public record Pair<T, U>(T first, U second) {
}
