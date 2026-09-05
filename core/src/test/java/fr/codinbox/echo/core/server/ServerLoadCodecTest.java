package fr.codinbox.echo.core.server;

import fr.codinbox.connector.commons.codec.JsonJacksonConnectorCodec;
import fr.codinbox.echo.api.server.ServerLoad;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import fr.codinbox.echo.api.server.ServerAdmissionSnapshot;
import fr.codinbox.echo.api.proxy.ProxyLoadSnapshot;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.client.handler.State;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ServerLoadCodecTest {

    @Test
    void admissionSnapshotRoundTripsWithRealCountsAndMemberClassification() throws Exception {
        final Instant now = Instant.parse("2026-09-05T12:00:00Z");
        final ServerAdmissionSnapshot snapshot = new ServerAdmissionSnapshot(
                Map.of(UUID.randomUUID(), true, UUID.randomUUID(), false), Map.of(UUID.randomUUID(), false),
                100, 120, now, now.plusSeconds(5));

        assertThat(roundTrip(snapshot)).isEqualTo(snapshot);
    }

    @Test
    void proxyThresholdIsTelemetryNotABackendHardCap() throws Exception {
        final Instant now = Instant.parse("2026-09-05T12:00:00Z");
        final ProxyLoadSnapshot snapshot = new ProxyLoadSnapshot(476, 450,
                ProxyLoadSnapshot.SCALE_OUT_THRESHOLD, now, now.plusSeconds(5));

        assertThat(roundTrip(snapshot)).isEqualTo(snapshot);
        assertThat(snapshot.scaleOutThreshold()).isEqualTo(475);
    }

    private Object roundTrip(final Object value) throws Exception {
        final JsonJacksonConnectorCodec codec = new JsonJacksonConnectorCodec();
        final ByteBuf encoded = codec.getValueEncoder().encode(value);
        try {
            return new JsonJacksonConnectorCodec().getValueDecoder().decode(encoded, new State());
        } finally {
            encoded.release();
        }
    }

    @Test
    void serverLoadSnapshotRoundTripsBetweenConnectorCodecInstances() throws Exception {
        Instant sampledAt = Instant.parse("2026-09-04T08:45:00Z");
        var snapshot = new ServerLoadSnapshot(
                new ServerLoad(12, true), sampledAt, sampledAt.plusSeconds(30));
        var writer = new JsonJacksonConnectorCodec();
        var reader = new JsonJacksonConnectorCodec();
        ByteBuf encoded = writer.getValueEncoder().encode(snapshot);
        ByteBuf readable = encoded.copy();

        try {
            assertThat(encoded.toString(StandardCharsets.UTF_8))
                    .contains("\"@class\":\"fr.codinbox.echo.api.server.ServerLoadSnapshot\"");
            Object decoded = reader.getValueDecoder().decode(readable, new State());
            assertThat(decoded).isEqualTo(snapshot);
        } finally {
            readable.release();
            encoded.release();
        }
    }

}
