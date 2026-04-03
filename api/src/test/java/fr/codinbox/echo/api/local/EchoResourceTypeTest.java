package fr.codinbox.echo.api.local;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class EchoResourceTypeTest {

    @Test
    void values_containsExpectedCount() {
        assertThat(EchoResourceType.values()).hasSize(2);
    }

    @Test
    void valueOf_proxy() {
        assertThat(EchoResourceType.valueOf("PROXY")).isEqualTo(EchoResourceType.PROXY);
    }

    @Test
    void valueOf_server() {
        assertThat(EchoResourceType.valueOf("SERVER")).isEqualTo(EchoResourceType.SERVER);
    }
}
