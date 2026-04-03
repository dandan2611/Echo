package fr.codinbox.echo.api.messaging.impl;

import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest.PlayerResponse;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest.ServerSwitchRequestStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
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

    @Test
    void response_storesResponses() {
        UUID uuid = UUID.randomUUID();
        PlayerResponse pr = new PlayerResponse(true, ServerSwitchRequestStatus.SUCCESS, null);
        Map<UUID, PlayerResponse> map = Map.of(uuid, pr);

        ServerSwitchRequest.Response response = new ServerSwitchRequest.Response(map);

        assertThat(response.getResponses()).containsKey(uuid);
        assertThat(response.getResponses().get(uuid).isSuccessful()).isTrue();
    }

    @Test
    void playerResponse_storesFields() {
        PlayerResponse pr = new PlayerResponse(false, ServerSwitchRequestStatus.SERVER_DISCONNECTED, "kicked");

        assertThat(pr.isSuccessful()).isFalse();
        assertThat(pr.getStatus()).isEqualTo(ServerSwitchRequestStatus.SERVER_DISCONNECTED);
        assertThat(pr.getSerializedReason()).isEqualTo("kicked");
    }

    @Test
    void serverSwitchRequestStatus_values() {
        assertThat(ServerSwitchRequestStatus.values()).containsExactly(
                ServerSwitchRequestStatus.SUCCESS,
                ServerSwitchRequestStatus.ALREADY_CONNECTED,
                ServerSwitchRequestStatus.CONNECTION_IN_PROGRESS,
                ServerSwitchRequestStatus.CONNECTION_CANCELLED,
                ServerSwitchRequestStatus.SERVER_DISCONNECTED
        );
    }
}
