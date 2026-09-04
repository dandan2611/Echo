package fr.codinbox.echo.api.administration;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.exception.user.UserHasNoProxyException;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.messaging.impl.ResourceControlRequest;
import fr.codinbox.echo.api.messaging.impl.UserDisconnectRequest;
import fr.codinbox.echo.api.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class RemoteAdministrationTest {

    private EchoClient echo;
    private MessagingProvider messaging;
    private RemoteAdministration administration;

    @BeforeEach
    void setUp() {
        this.echo = mock(EchoClient.class);
        this.messaging = mock(MessagingProvider.class);
        this.administration = new RemoteAdministration(this.echo, () -> 1_000L);
        when(this.echo.getMessagingProvider()).thenReturn(this.messaging);
        when(this.echo.getLocalTopic()).thenReturn("proxy:admin");
    }

    @Test
    void constructorsRejectNullDependencies() {
        assertThat(new RemoteAdministration(this.echo)).isNotNull();
        assertThatThrownBy(() -> new RemoteAdministration(null))
                .isInstanceOf(NullPointerException.class).hasMessage("echo");
        assertThatThrownBy(() -> new RemoteAdministration(this.echo, null))
                .isInstanceOf(NullPointerException.class).hasMessage("currentTimeMillis");
    }

    @Test
    void controlUsesOneExactServerTopicAndCallerTimeout() {
        ResourceControlRequest request = new ResourceControlRequest(
                ResourceControlRequest.Action.PING, EchoResourceType.SERVER, "game-1", null);
        ResourceControlRequest.Response expected = new ResourceControlRequest.Response(
                request, true, ResourceControlRequest.Status.ACCEPTED, "pong");
        when(this.messaging.request(eq("server:game-1"), eq(request),
                eq(ResourceControlRequest.Response.class), eq(Duration.ofSeconds(2))))
                .thenReturn(EchoFuture.completed(expected));

        assertThat(this.administration.control(request, Duration.ofSeconds(2)).join()).isSameAs(expected);
        assertThat(request.getReplyTopic()).isEqualTo("proxy:admin");
        assertThat(request.getExecutionDeadlineEpochMillis()).isEqualTo(3_000L);
    }

    @Test
    void controlRejectsInvalidRequestsAndTimeouts() {
        ResourceControlRequest invalid = new ResourceControlRequest();

        assertThatThrownBy(() -> this.administration.control(null, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class).hasMessage("request");
        assertThatThrownBy(() -> this.administration.control(invalid, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("action is required");
        assertThatThrownBy(() -> this.administration.control(validControlRequest(), null))
                .isInstanceOf(NullPointerException.class).hasMessage("timeout");
        assertThatThrownBy(() -> this.administration.control(validControlRequest(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("timeout must be at least 1ms");
        assertThatThrownBy(() -> this.administration.control(validControlRequest(), Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("timeout must be at least 1ms");
        assertThatThrownBy(() -> this.administration.control(validControlRequest(), Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("timeout must be at least 1ms");

        RemoteAdministration overflowing = new RemoteAdministration(this.echo, () -> Long.MAX_VALUE);
        assertThatThrownBy(() -> overflowing.control(validControlRequest(), Duration.ofMillis(1)))
                .isInstanceOf(ArithmeticException.class).hasMessage("long overflow");
    }

    @Test
    void controlPropagatesMessagingFailure() {
        ResourceControlRequest request = validControlRequest();
        IllegalStateException failure = new IllegalStateException("broker unavailable");
        when(this.messaging.request("server:game-1", request,
                ResourceControlRequest.Response.class, Duration.ofSeconds(1)))
                .thenReturn(failedFuture(failure));

        assertThatThrownBy(() -> this.administration.control(request, Duration.ofSeconds(1)).join())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("broker unavailable");
    }

    @Test
    void disconnectResolvesCurrentProxyAndUsesRemainingTimeoutBudget() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getCurrentProxyId()).thenReturn(EchoFuture.completed(Optional.of("proxy-2")));
        when(user.getSessionId()).thenReturn(EchoFuture.completed(Optional.of("session-2")));
        when(this.messaging.request(eq("proxy:proxy-2"), any(UserDisconnectRequest.class),
                eq(UserDisconnectRequest.Response.class), eq(Duration.ofMillis(2_750))))
                .thenAnswer(invocation -> {
                    UserDisconnectRequest request = invocation.getArgument(1);
                    return EchoFuture.completed(new UserDisconnectRequest.Response(
                            request, true, UserDisconnectRequest.Status.DISCONNECTED, "Player disconnected"));
                });

        AtomicLong now = new AtomicLong(1_000L);
        RemoteAdministration administration = new RemoteAdministration(this.echo, () -> now.getAndAdd(250L));

        administration.disconnect(user, "maintenance", Duration.ofSeconds(3)).join();

        ArgumentCaptor<UserDisconnectRequest> request = ArgumentCaptor.forClass(UserDisconnectRequest.class);
        verify(this.messaging).request(eq("proxy:proxy-2"), request.capture(),
                eq(UserDisconnectRequest.Response.class), eq(Duration.ofMillis(2_750)));
        assertThat(request.getValue().getExpectedProxyId()).isEqualTo("proxy-2");
        assertThat(request.getValue().getExpectedSessionId()).isEqualTo("session-2");
        assertThat(request.getValue().getUserId()).isEqualTo(userId);
        assertThat(request.getValue().getReason()).isEqualTo("maintenance");
        assertThat(request.getValue().getDeadlineEpochMillis()).isEqualTo(4_000L);
        assertThat(request.getValue().getReplyTopic()).isEqualTo("proxy:admin");
    }

    @Test
    void disconnectRejectsInvalidArgumentsAndOverflowingDeadline() {
        User user = user(EchoFuture.completed(Optional.of("proxy-1")));

        assertThatThrownBy(() -> this.administration.disconnect(null, "reason", Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class).hasMessage("user");
        assertThatThrownBy(() -> this.administration.disconnect(user, null, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class).hasMessage("reason");
        assertThatThrownBy(() -> this.administration.disconnect(user, " ", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("reason is required");
        assertThatThrownBy(() -> this.administration.disconnect(user, "reason", null))
                .isInstanceOf(NullPointerException.class).hasMessage("timeout");

        RemoteAdministration overflowing = new RemoteAdministration(this.echo, () -> Long.MAX_VALUE);
        assertThatThrownBy(() -> overflowing.disconnect(user, "reason", Duration.ofMillis(1)))
                .isInstanceOf(ArithmeticException.class).hasMessage("long overflow");
    }

    @Test
    void disconnectFailsWhenUserHasNoProxy() {
        User user = user(EchoFuture.completed(Optional.empty()));

        assertThatThrownBy(() -> this.administration.disconnect(user, "reason", Duration.ofSeconds(1)).join())
                .hasRootCauseInstanceOf(UserHasNoProxyException.class)
                .hasRootCauseMessage("User with id '00000000-0000-0000-0000-000000000002' has no proxy, "
                        + "it could be caused by a disconnection from the network");
        verify(this.messaging, never()).request(any(), any(), any(), any());
    }

    @Test
    void disconnectFailsWhenUserHasNoSession() {
        User user = user(EchoFuture.completed(Optional.of("proxy-1")));
        when(user.getSessionId()).thenReturn(EchoFuture.completed(Optional.empty()));

        assertThatThrownBy(() -> this.administration.disconnect(user, "reason", Duration.ofSeconds(1)).join())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("User with id '00000000-0000-0000-0000-000000000002' has no session");
        verify(this.messaging, never()).request(any(), any(), any(), any());
    }

    @Test
    void disconnectFailsWhenDeadlineElapsesDuringProxyLookup() {
        User user = user(EchoFuture.completed(Optional.of("proxy-1")));
        AtomicLong now = new AtomicLong(1_000L);
        RemoteAdministration administration = new RemoteAdministration(this.echo, () -> now.getAndAdd(1_000L));

        assertThatThrownBy(() -> administration.disconnect(user, "reason", Duration.ofSeconds(1)).join())
                .hasRootCauseInstanceOf(TimeoutException.class)
                .hasRootCauseMessage("User disconnect deadline elapsed");
        verify(this.messaging, never()).request(any(), any(), any(), any());
    }

    @Test
    void disconnectBoundsAnUnfinishedProxyLookup() {
        User user = user(new EchoFuture<>());

        assertThatThrownBy(() -> this.administration.disconnect(user, "reason", Duration.ofMillis(1)).join())
                .hasRootCauseInstanceOf(TimeoutException.class);
        verify(this.messaging, never()).request(any(), any(), any(), any());
    }

    @Test
    void disconnectPropagatesProxyLookupAndMessagingFailures() {
        IllegalStateException lookupFailure = new IllegalStateException("cache unavailable");
        assertThatThrownBy(() -> this.administration.disconnect(
                user(failedFuture(lookupFailure)), "reason", Duration.ofSeconds(1)).join())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("cache unavailable");

        IllegalStateException messagingFailure = new IllegalStateException("broker unavailable");
        User user = user(EchoFuture.completed(Optional.of("proxy-1")));
        when(this.messaging.request(eq("proxy:proxy-1"), any(UserDisconnectRequest.class),
                eq(UserDisconnectRequest.Response.class), eq(Duration.ofSeconds(1))))
                .thenReturn(failedFuture(messagingFailure));

        assertThatThrownBy(() -> this.administration.disconnect(user, "reason", Duration.ofSeconds(1)).join())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("broker unavailable");
    }

    private ResourceControlRequest validControlRequest() {
        return new ResourceControlRequest(ResourceControlRequest.Action.PING,
                EchoResourceType.SERVER, "game-1", null);
    }

    private User user(EchoFuture<Optional<String>> proxyId) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        when(user.getCurrentProxyId()).thenReturn(proxyId);
        when(user.getSessionId()).thenReturn(EchoFuture.completed(Optional.of("session-1")));
        return user;
    }

    private static <T> EchoFuture<T> failedFuture(Throwable failure) {
        EchoFuture<T> future = new EchoFuture<>();
        future.completeExceptionally(failure);
        return future;
    }
}
