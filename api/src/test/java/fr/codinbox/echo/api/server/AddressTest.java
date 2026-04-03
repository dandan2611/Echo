package fr.codinbox.echo.api.server;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class AddressTest {

    @Test
    void constructor_setsHostAndPort() {
        Address address = new Address("localhost", 25565);

        assertThat(address.getHost()).isEqualTo("localhost");
        assertThat(address.getPort()).isEqualTo(25565);
    }

    @Test
    void toInetSocketAddress_returnsCorrectAddress() {
        Address address = new Address("127.0.0.1", 8080);

        InetSocketAddress inet = address.toInetSocketAddress();

        assertThat(inet.getHostString()).isEqualTo("127.0.0.1");
        assertThat(inet.getPort()).isEqualTo(8080);
    }

    @Test
    void fromString_validAddress_parsesCorrectly() {
        Address address = Address.fromString("localhost:25565");

        assertThat(address.getHost()).isEqualTo("localhost");
        assertThat(address.getPort()).isEqualTo(25565);
    }

    @Test
    void fromString_noColon_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> Address.fromString("localhost"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromString_tooManyParts_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> Address.fromString("a:b:c"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromString_invalidPort_throwsNumberFormatException() {
        assertThatThrownBy(() -> Address.fromString("localhost:abc"))
                .isInstanceOf(NumberFormatException.class);
    }
}
