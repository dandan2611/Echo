package fr.codinbox.echo.velocity.messaging;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.impl.UserDisconnectRequest;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.velocity.EchoPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
class UserDisconnectRequestHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private EchoPlugin plugin;
    private EchoClient echo;
    private User user;
    private UserDisconnectRequestHandler handler;

    @BeforeEach
    void setUp() {
        this.plugin = mock(EchoPlugin.class);
        this.echo = mock(EchoClient.class);
        this.user = mock(User.class);
        this.handler = new UserDisconnectRequestHandler(
                this.plugin, this.echo, Clock.fixed(NOW, ZoneOffset.UTC));
        when(this.echo.getCurrentResourceType()).thenReturn(EchoResourceType.PROXY);
        when(this.echo.getCurrentResourceId()).thenReturn(Optional.of("proxy-1"));
        when(this.echo.getUserById(USER_ID)).thenReturn(EchoFuture.completed(Optional.of(this.user)));
        when(this.user.getSessionId()).thenReturn(EchoFuture.completed(Optional.of("session-1")));
    }

    @Test
    void invalidRequestIsRejectedBeforeTargetOrPlayerWork() {
        UserDisconnectRequest request = request("proxy-1", NOW.plusSeconds(5));
        when(request.validationError()).thenReturn("user id is required");

        this.handler.onReceive(request);

        assertResponse(request, false, UserDisconnectRequest.Status.INVALID_REQUEST);
        verifyNoInteractions(this.echo, this.plugin);
    }

    @Test
    void requestForAnotherProxyIsRejected() {
        UserDisconnectRequest request = request("proxy-2", NOW.plusSeconds(5));

        this.handler.onReceive(request);

        assertResponse(request, false, UserDisconnectRequest.Status.WRONG_TARGET);
        verify(this.plugin, never()).disconnectPlayer(
                USER_ID, "maintenance", "session-1", NOW.plusSeconds(5).toEpochMilli());
    }

    @Test
    void wrongLocalTypeAndMissingLocalIdAreRejected() {
        UserDisconnectRequest wrongType = request("proxy-1", NOW.plusSeconds(5));
        when(this.echo.getCurrentResourceType()).thenReturn(EchoResourceType.SERVER);

        this.handler.onReceive(wrongType);

        when(this.echo.getCurrentResourceType()).thenReturn(EchoResourceType.PROXY);
        UserDisconnectRequest missingId = request("proxy-1", NOW.plusSeconds(5));
        when(this.echo.getCurrentResourceId()).thenReturn(Optional.empty());

        this.handler.onReceive(missingId);

        assertResponse(wrongType, false, UserDisconnectRequest.Status.WRONG_TARGET);
        assertResponse(missingId, false, UserDisconnectRequest.Status.WRONG_TARGET);
        verifyNoInteractions(this.plugin);
    }

    @Test
    void expiredRequestIsRejectedBeforeScheduling() {
        UserDisconnectRequest request = request("proxy-1", NOW);

        this.handler.onReceive(request);

        assertResponse(request, false, UserDisconnectRequest.Status.EXPIRED);
        verify(this.plugin, never()).disconnectPlayer(
                USER_ID, "maintenance", "session-1", NOW.toEpochMilli());
    }

    @Test
    void absentPlayerGetsBoundedCorrelatedResponse() {
        UserDisconnectRequest request = request("proxy-1", NOW.plusSeconds(5));
        when(this.plugin.disconnectPlayer(
                USER_ID, "maintenance", "session-1", NOW.plusSeconds(5).toEpochMilli()))
                .thenReturn(CompletableFuture.completedFuture(false));

        this.handler.onReceive(request);

        UserDisconnectRequest.Response response = response(request);
        assertThat(response.getMessageId()).isEqualTo(request.getMessageId());
        assertThat(response.isAccepted()).isFalse();
        assertThat(response.getStatus()).isEqualTo(UserDisconnectRequest.Status.PLAYER_NOT_FOUND);
        assertThat(response.getMessage()).isNotBlank();
    }

    @Test
    void presentPlayerGetsDisconnectedResponse() {
        UserDisconnectRequest request = request("proxy-1", NOW.plusSeconds(5));
        when(this.plugin.disconnectPlayer(
                USER_ID, "maintenance", "session-1", NOW.plusSeconds(5).toEpochMilli()))
                .thenReturn(CompletableFuture.completedFuture(true));

        this.handler.onReceive(request);

        assertResponse(request, true, UserDisconnectRequest.Status.DISCONNECTED);
        verify(this.plugin).disconnectPlayer(
                USER_ID, "maintenance", "session-1", NOW.plusSeconds(5).toEpochMilli());
    }

    @Test
    void replacedPersistedSessionIsRejectedBeforeScheduling() {
        UserDisconnectRequest request = request("proxy-1", NOW.plusSeconds(5));
        when(this.user.getSessionId()).thenReturn(EchoFuture.completed(Optional.of("session-2")));

        this.handler.onReceive(request);

        assertResponse(request, false, UserDisconnectRequest.Status.PLAYER_NOT_FOUND);
        verifyNoInteractions(this.plugin);
    }

    @Test
    void missingUserOrSessionIsRejectedBeforeScheduling() {
        UserDisconnectRequest missingUser = request("proxy-1", NOW.plusSeconds(5));
        when(this.echo.getUserById(USER_ID)).thenReturn(EchoFuture.completed(Optional.empty()));

        this.handler.onReceive(missingUser);

        assertResponse(missingUser, false, UserDisconnectRequest.Status.PLAYER_NOT_FOUND);

        UserDisconnectRequest missingSession = request("proxy-1", NOW.plusSeconds(5));
        when(this.echo.getUserById(USER_ID)).thenReturn(EchoFuture.completed(Optional.of(this.user)));
        when(this.user.getSessionId()).thenReturn(EchoFuture.completed(Optional.empty()));

        this.handler.onReceive(missingSession);

        assertResponse(missingSession, false, UserDisconnectRequest.Status.PLAYER_NOT_FOUND);
        verifyNoInteractions(this.plugin);
    }

    @Test
    void asynchronousFailureIsUnwrappedAndCorrelated() {
        UserDisconnectRequest request = request("proxy-1", NOW.plusSeconds(5));
        CompletableFuture<Boolean> disconnect = new CompletableFuture<>();
        when(this.plugin.disconnectPlayer(
                USER_ID, "maintenance", "session-1", NOW.plusSeconds(5).toEpochMilli())).thenReturn(disconnect);

        this.handler.onReceive(request);
        verify(request, never()).reply(any(UserDisconnectRequest.Response.class));
        disconnect.completeExceptionally(new CompletionException(
                new IllegalStateException("disconnect failed")));

        UserDisconnectRequest.Response response = assertResponse(
                request, false, UserDisconnectRequest.Status.FAILED);
        assertThat(response.getMessageId()).isEqualTo(request.getMessageId());
        assertThat(response.getMessage()).isEqualTo("disconnect failed");
    }

    @Test
    void asynchronousFailureWithoutCauseUsesTheWrapperType() {
        UserDisconnectRequest request = request("proxy-1", NOW.plusSeconds(5));
        CompletableFuture<Boolean> disconnect = new CompletableFuture<>();
        when(this.plugin.disconnectPlayer(
                USER_ID, "maintenance", "session-1", NOW.plusSeconds(5).toEpochMilli())).thenReturn(disconnect);

        this.handler.onReceive(request);
        disconnect.completeExceptionally(new CompletionException((Throwable) null));

        UserDisconnectRequest.Response response = assertResponse(
                request, false, UserDisconnectRequest.Status.FAILED);
        assertThat(response.getMessage()).isEqualTo("CompletionException");
    }

    @Test
    void neverCompletingDisconnectTimesOutWithoutWaiting() {
        UserDisconnectRequest request = request("proxy-1", NOW.plusSeconds(5));
        CompletableFuture<Boolean> disconnect = new CompletableFuture<>() {
            @Override
            public CompletableFuture<Boolean> copy() {
                return new CompletableFuture<>() {
                    @Override
                    public CompletableFuture<Boolean> orTimeout(long timeout, TimeUnit unit) {
                        assertThat(timeout).isEqualTo(5_000L);
                        assertThat(unit).isEqualTo(TimeUnit.MILLISECONDS);
                        this.completeExceptionally(new TimeoutException("disconnect deadline elapsed"));
                        return this;
                    }
                };
            }
        };
        when(this.plugin.disconnectPlayer(
                USER_ID, "maintenance", "session-1", NOW.plusSeconds(5).toEpochMilli())).thenReturn(disconnect);

        this.handler.onReceive(request);

        UserDisconnectRequest.Response response = assertResponse(
                request, false, UserDisconnectRequest.Status.TIMED_OUT);
        assertThat(response.getMessage()).isEqualTo("disconnect deadline elapsed");
        assertThat(disconnect).isNotDone();
    }

    @Test
    void synchronousDisconnectFailuresAreReportedWithOrWithoutMessages() {
        UserDisconnectRequest withMessage = request("proxy-1", NOW.plusSeconds(5));
        doThrow(new IllegalStateException("scheduler unavailable"))
                .doThrow(new IllegalStateException())
                .when(this.plugin).disconnectPlayer(
                        USER_ID, "maintenance", "session-1", NOW.plusSeconds(5).toEpochMilli());

        this.handler.onReceive(withMessage);

        UserDisconnectRequest.Response withMessageResponse = assertResponse(
                withMessage, false, UserDisconnectRequest.Status.FAILED);
        assertThat(withMessageResponse.getMessage()).isEqualTo("scheduler unavailable");

        UserDisconnectRequest withoutMessage = request("proxy-1", NOW.plusSeconds(5));

        this.handler.onReceive(withoutMessage);

        UserDisconnectRequest.Response withoutMessageResponse = assertResponse(
                withoutMessage, false, UserDisconnectRequest.Status.FAILED);
        assertThat(withoutMessageResponse.getMessage()).isEqualTo("IllegalStateException");
    }

    @Test
    void synchronousLookupFailuresAreReportedWithOrWithoutMessages() {
        UserDisconnectRequest withMessage = request("proxy-1", NOW.plusSeconds(5));
        UserDisconnectRequest withoutMessage = request("proxy-1", NOW.plusSeconds(5));
        when(this.echo.getUserById(USER_ID))
                .thenThrow(new IllegalStateException("lookup unavailable"))
                .thenThrow(new IllegalStateException());

        this.handler.onReceive(withMessage);
        this.handler.onReceive(withoutMessage);

        assertThat(assertResponse(withMessage, false, UserDisconnectRequest.Status.FAILED).getMessage())
                .isEqualTo("lookup unavailable");
        assertThat(assertResponse(withoutMessage, false, UserDisconnectRequest.Status.FAILED).getMessage())
                .isEqualTo("IllegalStateException");
    }

    private static UserDisconnectRequest request(String proxyId, Instant deadline) {
        UserDisconnectRequest request = mock(UserDisconnectRequest.class);
        when(request.validationError()).thenReturn(null);
        when(request.getExpectedProxyId()).thenReturn(proxyId);
        when(request.getExpectedSessionId()).thenReturn("session-1");
        when(request.getUserId()).thenReturn(USER_ID);
        when(request.getReason()).thenReturn("maintenance");
        when(request.getDeadlineEpochMillis()).thenReturn(deadline.toEpochMilli());
        when(request.getMessageId()).thenReturn(REQUEST_ID);
        return request;
    }

    private static UserDisconnectRequest.Response response(UserDisconnectRequest request) {
        ArgumentCaptor<UserDisconnectRequest.Response> response =
                ArgumentCaptor.forClass(UserDisconnectRequest.Response.class);
        verify(request).reply(response.capture());
        return response.getValue();
    }

    private static UserDisconnectRequest.Response assertResponse(
            UserDisconnectRequest request, boolean accepted, UserDisconnectRequest.Status status) {
        UserDisconnectRequest.Response response = response(request);
        assertThat(response.isAccepted()).isEqualTo(accepted);
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getMessage()).isNotBlank();
        return response;
    }
}
