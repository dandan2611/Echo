package fr.codinbox.echo.api.utils;

/**
 * A generic pair of two values.
 *
 * <pre>{@code
 * Pair<String, Integer> pair = new Pair<>("hello", 42);
 * String first = pair.first();   // "hello"
 * Integer second = pair.second(); // 42
 * }</pre>
 *
 * @param first  the first value
 * @param second the second value
 * @param <T>    the type of the first value
 * @param <U>    the type of the second value
 */
public record Pair<T, U>(T first, U second) {
}
