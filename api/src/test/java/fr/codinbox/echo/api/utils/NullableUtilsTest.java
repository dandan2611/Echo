package fr.codinbox.echo.api.utils;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class NullableUtilsTest {

    @Test
    void requireNonNull_withNonNull_returnsValue() {
        String result = NullableUtils.requireNonNull("hello", RuntimeException.class);

        assertThat(result).isEqualTo("hello");
    }

    @Test
    void requireNonNull_withNull_throwsException() {
        assertThatThrownBy(() -> NullableUtils.requireNonNull(null, RuntimeException.class))
                .isInstanceOf(RuntimeException.class);
    }
}
