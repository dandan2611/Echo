package fr.codinbox.echo.api.messaging.impl;

import fr.codinbox.echo.api.local.EchoResourceType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ResourceControlRequestTest {

    @Test
    void drainDurationIsPositiveAndResolvedAtReceipt() {
        ResourceControlRequest request = new ResourceControlRequest(
                ResourceControlRequest.Action.DRAIN, EchoResourceType.SERVER, "game-1",
                Duration.ofSeconds(5), "maintenance");
        request.setExecutionDeadlineEpochMillis(10_000L);

        assertThat(request.resolveDrainDeadlineEpochMillis(1_000L)).isEqualTo(6_000L);
        assertThat(request.getAction()).isEqualTo(ResourceControlRequest.Action.DRAIN);
        assertThat(request.getExpectedResourceType()).isEqualTo(EchoResourceType.SERVER);
        assertThat(request.getExpectedResourceId()).isEqualTo("game-1");
        assertThat(request.getDeadlineDurationMillis()).isEqualTo(5_000L);
        assertThat(request.getDeadlineEpochMillis()).isZero();
        assertThat(request.getExecutionDeadlineEpochMillis()).isEqualTo(10_000L);
        assertThat(request.getReason()).isEqualTo("maintenance");
        assertThat(request.validationError()).isNull();
        assertThatThrownBy(() -> new ResourceControlRequest(
                ResourceControlRequest.Action.DRAIN, EchoResourceType.SERVER, "game-1",
                Duration.ZERO, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceControlRequest(
                ResourceControlRequest.Action.DRAIN, EchoResourceType.SERVER, "game-1",
                Duration.ofSeconds(-1), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceControlRequest(
                ResourceControlRequest.Action.DRAIN, EchoResourceType.SERVER, "game-1",
                Duration.ofNanos(1), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceControlRequest(
                ResourceControlRequest.Action.DRAIN, EchoResourceType.SERVER, "game-1",
                (Duration) null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void absoluteAndAbsentDrainDeadlinesResolveWithoutReceiverTimeAdjustment() {
        ResourceControlRequest request = new ResourceControlRequest(
                ResourceControlRequest.Action.PING, EchoResourceType.PROXY, "proxy-1",
                Instant.ofEpochMilli(5_000L), null);
        request.setExecutionDeadlineEpochMillis(10_000L);

        assertThat(request.resolveDrainDeadlineEpochMillis(123L)).isEqualTo(5_000L);
        assertThat(request.validationError()).isNull();

        ResourceControlRequest unbounded = new ResourceControlRequest(
                ResourceControlRequest.Action.PING, EchoResourceType.PROXY, "proxy-1", null);
        unbounded.setExecutionDeadlineEpochMillis(10_000L);
        assertThat(unbounded.resolveDrainDeadlineEpochMillis(123L)).isZero();
        assertThat(unbounded.validationError()).isNull();

        assertThatThrownBy(() -> new ResourceControlRequest(
                ResourceControlRequest.Action.PING, EchoResourceType.PROXY, "proxy-1",
                Instant.EPOCH, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceControlRequest(
                ResourceControlRequest.Action.PING, EchoResourceType.PROXY, "proxy-1",
                (Instant) null, null)).isInstanceOf(NullPointerException.class).hasMessage("deadline");
    }

    @Test
    void constructorsRejectNullAndBlankIdentityFields() {
        assertThatThrownBy(() -> new ResourceControlRequest(
                null, EchoResourceType.SERVER, "game-1", null))
                .isInstanceOf(NullPointerException.class).hasMessage("action");
        assertThatThrownBy(() -> new ResourceControlRequest(
                ResourceControlRequest.Action.PING, null, "game-1", null))
                .isInstanceOf(NullPointerException.class).hasMessage("expectedResourceType");
        assertThatThrownBy(() -> new ResourceControlRequest(
                ResourceControlRequest.Action.PING, EchoResourceType.SERVER, null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("expectedResourceId");
        assertThatThrownBy(() -> new ResourceControlRequest(
                ResourceControlRequest.Action.PING, EchoResourceType.SERVER, " ", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("expected resource id is required");
    }

    @Test
    void jacksonSettersExposeEveryValidationError() {
        ResourceControlRequest request = new ResourceControlRequest();
        assertThat(request.validationError()).isEqualTo("action is required");

        assertThatThrownBy(() -> request.setAction(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> request.setExpectedResourceType(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> request.setExpectedResourceId(null)).isInstanceOf(NullPointerException.class);

        request.setAction(ResourceControlRequest.Action.ACTIVATE);
        assertThat(request.validationError()).isEqualTo("expected resource type is required");
        request.setExpectedResourceType(EchoResourceType.SERVER);
        assertThat(request.validationError()).isEqualTo("expected resource id is required");
        request.setExpectedResourceId(" ");
        assertThat(request.validationError()).isEqualTo("expected resource id is required");
        request.setExpectedResourceId("game-1");
        assertThat(request.validationError()).isEqualTo("execution deadline must be positive");
        request.setExecutionDeadlineEpochMillis(1L);
        request.setDeadlineDurationMillis(-1L);
        assertThat(request.validationError()).isEqualTo("deadline must be positive");
        request.setDeadlineDurationMillis(0L);
        request.setDeadlineEpochMillis(-1L);
        assertThat(request.validationError()).isEqualTo("deadline must be positive");
        request.setDeadlineDurationMillis(1L);
        request.setDeadlineEpochMillis(1L);
        assertThat(request.validationError()).isEqualTo(
                "deadline duration and absolute deadline are mutually exclusive");
        request.setDeadlineEpochMillis(0L);
        assertThat(request.validationError()).isNull();
    }

    @Test
    void relativeDeadlineOverflowIsReported() {
        ResourceControlRequest request = new ResourceControlRequest(
                ResourceControlRequest.Action.PING, EchoResourceType.SERVER, "game-1",
                Duration.ofMillis(1), null);

        assertThatThrownBy(() -> request.resolveDrainDeadlineEpochMillis(Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class).hasMessage("long overflow");
    }

    @Test
    void responseCopiesCorrelationAndAlwaysCarriesOutcome() {
        ResourceControlRequest request = new ResourceControlRequest(
                ResourceControlRequest.Action.PING, EchoResourceType.PROXY, "proxy-1", null);
        UUID correlation = UUID.fromString("00000000-0000-0000-0000-000000000001");
        request.setMessageId(correlation);

        ResourceControlRequest.Response response = new ResourceControlRequest.Response(
                request, true, ResourceControlRequest.Status.ACCEPTED, "pong");

        assertThat(response.getMessageId()).isEqualTo(correlation);
        assertThat(response.isAccepted()).isTrue();
        assertThat(response.getStatus()).isEqualTo(ResourceControlRequest.Status.ACCEPTED);
        assertThat(response.getMessage()).isEqualTo("pong");
    }

    @Test
    void responseSupportsJacksonSetters() {
        ResourceControlRequest.Response response = new ResourceControlRequest.Response();
        assertThat(response.isAccepted()).isFalse();
        assertThat(response.getStatus()).isNull();
        assertThat(response.getMessage()).isNull();

        response.setAccepted(true);
        response.setStatus(ResourceControlRequest.Status.TIMED_OUT);
        response.setMessage("timed out");

        assertThat(response.isAccepted()).isTrue();
        assertThat(response.getStatus()).isEqualTo(ResourceControlRequest.Status.TIMED_OUT);
        assertThat(response.getMessage()).isEqualTo("timed out");
        assertThatThrownBy(() -> response.setStatus(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> response.setMessage(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void responseConstructorRejectsNullRequiredValues() {
        ResourceControlRequest request = new ResourceControlRequest(
                ResourceControlRequest.Action.PING, EchoResourceType.PROXY, "proxy-1", null);

        assertThatThrownBy(() -> new ResourceControlRequest.Response(
                request, false, null, "failed"))
                .isInstanceOf(NullPointerException.class).hasMessage("status");
        assertThatThrownBy(() -> new ResourceControlRequest.Response(
                request, false, ResourceControlRequest.Status.FAILED, null))
                .isInstanceOf(NullPointerException.class).hasMessage("message");
        assertThatThrownBy(() -> new ResourceControlRequest.Response(
                null, false, ResourceControlRequest.Status.FAILED, "failed"))
                .isInstanceOf(NullPointerException.class).hasMessage("request");
    }
}
