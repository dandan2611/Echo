package fr.codinbox.echo.api.messaging.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ProxySwitchRequestTest {

    @Test
    void constructor_withVarargs_setsFields() {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();

        ProxySwitchRequest request = new ProxySwitchRequest("proxy-1", uuid1, uuid2);

        assertThat(request.getProxyId()).isEqualTo("proxy-1");
        assertThat(request.getUserUuids()).containsExactly(uuid1, uuid2);
    }

    @Test
    void constructor_singleUuid_wrapsInArray() {
        UUID uuid = UUID.randomUUID();

        ProxySwitchRequest request = new ProxySwitchRequest("proxy-1", uuid);

        assertThat(request.getUserUuids()).containsExactly(uuid);
    }
}
