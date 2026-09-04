package fr.codinbox.echo.api.messaging.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ServerSwitchRequestTest {

    @Test
    void constructor_setsFields() {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();

        ServerSwitchRequest request = new ServerSwitchRequest("lobby", uuid1, uuid2);

        assertThat(request.getServerId()).isEqualTo("lobby");
        assertThat(request.getUserUuids()).containsExactly(uuid1, uuid2);
    }

    @Test
    void singleUuidConstructor_wrapsInArray() {
        UUID uuid = UUID.randomUUID();

        ServerSwitchRequest request = new ServerSwitchRequest("lobby", uuid);

        assertThat(request.getUserUuids()).containsExactly(uuid);
    }

}
