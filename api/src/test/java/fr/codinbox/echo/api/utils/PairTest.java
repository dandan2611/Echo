package fr.codinbox.echo.api.utils;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class PairTest {

    @Test
    void storesValues() {
        Pair<String, Integer> pair = new Pair<>("hello", 42);

        assertThat(pair.first()).isEqualTo("hello");
        assertThat(pair.second()).isEqualTo(42);
    }

    @Test
    void nullValues() {
        Pair<String, String> pair = new Pair<>(null, null);

        assertThat(pair.first()).isNull();
        assertThat(pair.second()).isNull();
    }

    @Test
    void equals_samePairs() {
        Pair<String, Integer> a = new Pair<>("a", 1);
        Pair<String, Integer> b = new Pair<>("a", 1);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void hashCode_samePairs() {
        Pair<String, Integer> a = new Pair<>("a", 1);
        Pair<String, Integer> b = new Pair<>("a", 1);

        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
