package fr.codinbox.echo.api.messaging.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class UserDisconnectRequestTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void constructorStoresAValidBoundedRequest() {
        UserDisconnectRequest request = new UserDisconnectRequest(
                "proxy-1", "session-1", USER_ID, "maintenance", 5_000L);

        assertThat(request.getExpectedProxyId()).isEqualTo("proxy-1");
        assertThat(request.getExpectedSessionId()).isEqualTo("session-1");
        assertThat(request.getUserId()).isEqualTo(USER_ID);
        assertThat(request.getReason()).isEqualTo("maintenance");
        assertThat(request.getDeadlineEpochMillis()).isEqualTo(5_000L);
        assertThat(request.validationError()).isNull();
    }

    @Test
    void constructorRejectsInvalidRequiredValues() {
        assertThatThrownBy(() -> new UserDisconnectRequest(null, "session-1", USER_ID, "reason", 1L))
                .isInstanceOf(NullPointerException.class).hasMessage("expectedProxyId");
        assertThatThrownBy(() -> new UserDisconnectRequest(" ", "session-1", USER_ID, "reason", 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("expectedProxyId is required");
        assertThatThrownBy(() -> new UserDisconnectRequest("proxy-1", null, USER_ID, "reason", 1L))
                .isInstanceOf(NullPointerException.class).hasMessage("expectedSessionId");
        assertThatThrownBy(() -> new UserDisconnectRequest("proxy-1", " ", USER_ID, "reason", 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("expectedSessionId is required");
        assertThatThrownBy(() -> new UserDisconnectRequest("proxy-1", "session-1", null, "reason", 1L))
                .isInstanceOf(NullPointerException.class).hasMessage("userId");
        assertThatThrownBy(() -> new UserDisconnectRequest("proxy-1", "session-1", USER_ID, null, 1L))
                .isInstanceOf(NullPointerException.class).hasMessage("reason");
        assertThatThrownBy(() -> new UserDisconnectRequest("proxy-1", "session-1", USER_ID, " ", 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("reason is required");
        assertThatThrownBy(() -> new UserDisconnectRequest("proxy-1", "session-1", USER_ID, "reason", 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("deadline must be positive");
    }

    @Test
    void jacksonSettersExposeEveryValidationError() {
        UserDisconnectRequest request = new UserDisconnectRequest();
        assertThat(request.validationError()).isEqualTo("expected proxy id is required");

        assertThatThrownBy(() -> request.setExpectedProxyId(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> request.setExpectedSessionId(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> request.setUserId(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> request.setReason(null)).isInstanceOf(NullPointerException.class);

        request.setExpectedProxyId(" ");
        assertThat(request.validationError()).isEqualTo("expected proxy id is required");
        request.setExpectedProxyId("proxy-1");
        assertThat(request.validationError()).isEqualTo("expected session id is required");
        request.setExpectedSessionId("session-1");
        assertThat(request.validationError()).isEqualTo("user id is required");
        request.setUserId(USER_ID);
        assertThat(request.validationError()).isEqualTo("reason is required");
        request.setReason(" ");
        assertThat(request.validationError()).isEqualTo("reason is required");
        request.setReason("maintenance");
        assertThat(request.validationError()).isEqualTo("deadline must be positive");
        request.setDeadlineEpochMillis(1L);
        assertThat(request.validationError()).isNull();
    }

    @Test
    void responseCopiesCorrelationAndCarriesOutcome() {
        UserDisconnectRequest request = new UserDisconnectRequest(
                "proxy-1", "session-1", USER_ID, "maintenance", 5_000L);
        UUID correlation = UUID.fromString("00000000-0000-0000-0000-000000000002");
        request.setMessageId(correlation);

        UserDisconnectRequest.Response response = new UserDisconnectRequest.Response(
                request, true, UserDisconnectRequest.Status.DISCONNECTED, "disconnected");

        assertThat(response.getMessageId()).isEqualTo(correlation);
        assertThat(response.isAccepted()).isTrue();
        assertThat(response.getStatus()).isEqualTo(UserDisconnectRequest.Status.DISCONNECTED);
        assertThat(response.getMessage()).isEqualTo("disconnected");
    }

    @Test
    void responseSupportsJacksonSetters() {
        UserDisconnectRequest.Response response = new UserDisconnectRequest.Response();
        assertThat(response.isAccepted()).isFalse();
        assertThat(response.getStatus()).isNull();
        assertThat(response.getMessage()).isNull();

        response.setAccepted(true);
        response.setStatus(UserDisconnectRequest.Status.TIMED_OUT);
        response.setMessage("timed out");

        assertThat(response.isAccepted()).isTrue();
        assertThat(response.getStatus()).isEqualTo(UserDisconnectRequest.Status.TIMED_OUT);
        assertThat(response.getMessage()).isEqualTo("timed out");
        assertThatThrownBy(() -> response.setStatus(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> response.setMessage(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void responseConstructorRejectsNullRequiredValues() {
        UserDisconnectRequest request = new UserDisconnectRequest(
                "proxy-1", "session-1", USER_ID, "maintenance", 5_000L);

        assertThatThrownBy(() -> new UserDisconnectRequest.Response(request, false, null, "failed"))
                .isInstanceOf(NullPointerException.class).hasMessage("status");
        assertThatThrownBy(() -> new UserDisconnectRequest.Response(
                request, false, UserDisconnectRequest.Status.FAILED, null))
                .isInstanceOf(NullPointerException.class).hasMessage("message");
        assertThatThrownBy(() -> new UserDisconnectRequest.Response(
                null, false, UserDisconnectRequest.Status.FAILED, "failed"))
                .isInstanceOf(NullPointerException.class).hasMessage("request");
    }
}
