package fr.codinbox.echo.api.property;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class PropertyKeyTest {

    @Test
    void of_createsKey() {
        PropertyKey<String> key = PropertyKey.of("my.key", String.class);

        assertThat(key.key()).isEqualTo("my.key");
    }

    @Test
    void toString_returnsKey() {
        PropertyKey<String> key = PropertyKey.of("my.key", String.class);

        assertThat(key.toString()).isEqualTo("my.key");
    }

    @Test
    void equals_sameKey_returnsTrue() {
        PropertyKey<String> key1 = PropertyKey.of("my.key", String.class);
        PropertyKey<String> key2 = PropertyKey.of("my.key", String.class);

        assertThat(key1).isEqualTo(key2);
    }

    @Test
    void equals_differentKey_returnsFalse() {
        PropertyKey<String> key1 = PropertyKey.of("key.a", String.class);
        PropertyKey<String> key2 = PropertyKey.of("key.b", String.class);

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void equals_null_returnsFalse() {
        PropertyKey<String> key = PropertyKey.of("my.key", String.class);

        assertThat(key).isNotEqualTo(null);
    }

    @Test
    void hashCode_consistency() {
        PropertyKey<String> key1 = PropertyKey.of("my.key", String.class);
        PropertyKey<String> key2 = PropertyKey.of("my.key", String.class);

        assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
    }
}
