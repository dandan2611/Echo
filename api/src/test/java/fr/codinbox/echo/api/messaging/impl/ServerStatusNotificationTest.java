package fr.codinbox.echo.api.messaging.impl;

import fr.codinbox.echo.api.server.Address;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ServerStatusNotificationTest {

    @Test
    void constructor_withIdAddressStatus_setsFields() {
        Address address = new Address("localhost", 25565);

        ServerStatusNotification notification = new ServerStatusNotification(
                "server-1", address, ServerStatusNotification.Status.REGISTERED);

        assertThat(notification.getId()).isEqualTo("server-1");
        assertThat(notification.getAddress()).isSameAs(address);
        assertThat(notification.getStatus()).isEqualTo(ServerStatusNotification.Status.REGISTERED);
    }

    @Test
    void status_values() {
        assertThat(ServerStatusNotification.Status.values()).containsExactly(
                ServerStatusNotification.Status.REGISTERED,
                ServerStatusNotification.Status.UNREGISTERED
        );
    }
}
