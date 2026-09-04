package fr.codinbox.echo.paper.messaging;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.impl.ResourceControlRequest;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import fr.codinbox.echo.paper.EchoPaper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
class ResourceControlRequestHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private EchoPaper plugin;
    private EchoClient echo;
    private ResourceControlRequestHandler handler;

    @BeforeEach
    void setUp() {
        this.plugin = mock(EchoPaper.class);
        this.echo = mock(EchoClient.class);
        this.handler = new ResourceControlRequestHandler(this.plugin, this.echo, CLOCK);
        when(this.echo.getCurrentResourceType()).thenReturn(EchoResourceType.SERVER);
        when(this.echo.getCurrentResourceId()).thenReturn(Optional.of("game-1"));
    }

    @Test
    void constructorsAcceptDependenciesAndRejectNulls() {
        assertThat(new ResourceControlRequestHandler(this.plugin, this.echo)).isNotNull();
        assertThatThrownBy(() -> new ResourceControlRequestHandler(null, this.echo, CLOCK))
                .isInstanceOf(NullPointerException.class).hasMessage("plugin");
        assertThatThrownBy(() -> new ResourceControlRequestHandler(this.plugin, null, CLOCK))
                .isInstanceOf(NullPointerException.class).hasMessage("echo");
        assertThatThrownBy(() -> new ResourceControlRequestHandler(this.plugin, this.echo, null))
                .isInstanceOf(NullPointerException.class).hasMessage("clock");
    }

    @Test
    void invalidRequestIsRejectedBeforeTargetLookup() {
        ResourceControlRequest request = request(new ResourceControlRequest());

        this.handler.onReceive(request);

        assertResponse(request, false, ResourceControlRequest.Status.INVALID_REQUEST, "action is required");
        verifyNoInteractions(this.plugin);
        verify(this.echo, never()).getCurrentResourceType();
    }

    @Test
    void wrongExpectedResourceTypeIsRejectedWithoutLifecycleWork() {
        ResourceControlRequest request = request(
                ResourceControlRequest.Action.SHUTDOWN, EchoResourceType.PROXY, "game-1");

        this.handler.onReceive(request);

        assertResponse(request, false, ResourceControlRequest.Status.WRONG_TARGET,
                "Request targets another resource");
        verifyNoInteractions(this.plugin);
        verify(this.echo, never()).getCurrentResourceType();
    }

    @Test
    void wrongLocalResourceTypeIsRejectedWithoutIdentityLookup() {
        when(this.echo.getCurrentResourceType()).thenReturn(EchoResourceType.PROXY);
        ResourceControlRequest request = request(ResourceControlRequest.Action.SHUTDOWN);

        this.handler.onReceive(request);

        assertResponse(request, false, ResourceControlRequest.Status.WRONG_TARGET,
                "Request targets another resource");
        verifyNoInteractions(this.plugin);
        verify(this.echo, never()).getCurrentResourceId();
    }

    @Test
    void wrongResourceIdIsRejectedWithoutLifecycleWork() {
        ResourceControlRequest request = request(
                ResourceControlRequest.Action.SHUTDOWN, EchoResourceType.SERVER, "game-2");

        this.handler.onReceive(request);

        assertResponse(request, false, ResourceControlRequest.Status.WRONG_TARGET,
                "Request targets another resource");
        verifyNoInteractions(this.plugin);
    }

    @Test
    void missingLocalIdentityIsRejectedWithoutLifecycleWork() {
        when(this.echo.getCurrentResourceId()).thenReturn(Optional.empty());
        ResourceControlRequest request = request(ResourceControlRequest.Action.SHUTDOWN);

        this.handler.onReceive(request);

        assertResponse(request, false, ResourceControlRequest.Status.WRONG_TARGET,
                "Request targets another resource");
        verifyNoInteractions(this.plugin);
    }

    @Test
    void pingRepliesWithCorrelation() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.PING);

        this.handler.onReceive(request);

        assertResponse(request, true, ResourceControlRequest.Status.ACCEPTED, "pong");
        verifyNoInteractions(this.plugin);
    }

    @Test
    void oversizedRelativeDeadlineIsInvalid() {
        ResourceControlRequest request = request(
                ResourceControlRequest.Action.DRAIN, Duration.ofMillis(Long.MAX_VALUE));

        this.handler.onReceive(request);

        assertResponse(request, false, ResourceControlRequest.Status.INVALID_REQUEST,
                "deadline is too large");
        verifyNoInteractions(this.plugin);
    }

    @Test
    void expiredExecutionDeadlineStopsShutdownAndActivationBeforeLifecycleWork() {
        ResourceControlRequest shutdown = request(ResourceControlRequest.Action.SHUTDOWN);
        ResourceControlRequest activate = request(ResourceControlRequest.Action.ACTIVATE);
        shutdown.setExecutionDeadlineEpochMillis(NOW.toEpochMilli());
        activate.setExecutionDeadlineEpochMillis(NOW.toEpochMilli() - 1);

        this.handler.onReceive(shutdown);
        this.handler.onReceive(activate);

        assertResponse(shutdown, false, ResourceControlRequest.Status.EXPIRED,
                "Request deadline elapsed");
        assertResponse(activate, false, ResourceControlRequest.Status.EXPIRED,
                "Request deadline elapsed");
        verifyNoInteractions(this.plugin);
    }

    @Test
    void zeroDeadlineUsesDefaultReplyWindowAndUnboundedDrain() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.DRAIN);
        when(this.plugin.beginDrain()).thenReturn(CompletableFuture.completedFuture(null));

        this.handler.onReceive(request);

        verify(this.plugin).beginDrain();
        verify(this.plugin, never()).beginDrain(any(Instant.class));
        assertResponse(request, true, ResourceControlRequest.Status.ACCEPTED, "Server drain started");
    }

    @Test
    void relativeDeadlineIsResolvedAgainstTheHandlerClock() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.DRAIN, Duration.ofSeconds(30));
        when(this.plugin.beginDrain(NOW.plusSeconds(30)))
                .thenReturn(CompletableFuture.completedFuture(null));

        this.handler.onReceive(request);

        verify(this.plugin).beginDrain(NOW.plusSeconds(30));
        assertResponse(request, true, ResourceControlRequest.Status.ACCEPTED, "Server drain started");
    }

    @Test
    void absoluteDeadlineIsPassedToDrain() {
        Instant deadline = NOW.plusSeconds(45);
        ResourceControlRequest request = request(ResourceControlRequest.Action.DRAIN, deadline);
        when(this.plugin.beginDrain(deadline)).thenReturn(CompletableFuture.completedFuture(null));

        this.handler.onReceive(request);

        verify(this.plugin).beginDrain(deadline);
        assertResponse(request, true, ResourceControlRequest.Status.ACCEPTED, "Server drain started");
    }

    @Test
    void refreshLoadAcceptanceWaitsForAsyncCompletion() {
        CompletableFuture<ServerLoadSnapshot> refreshed = new CompletableFuture<>();
        ResourceControlRequest request = request(ResourceControlRequest.Action.REFRESH_LOAD);
        when(this.plugin.refreshLoad()).thenReturn(refreshed);

        this.handler.onReceive(request);

        verify(request, never()).reply(any(ResourceControlRequest.Response.class));
        refreshed.complete(mock(ServerLoadSnapshot.class));
        assertResponse(request, true, ResourceControlRequest.Status.ACCEPTED, "Server load refreshed");
    }

    @Test
    void activationReportsAcceptedAndRejectedOutcomes() {
        ResourceControlRequest accepted = request(ResourceControlRequest.Action.ACTIVATE);
        ResourceControlRequest rejected = request(ResourceControlRequest.Action.ACTIVATE);
        when(this.plugin.activate()).thenReturn(
                CompletableFuture.completedFuture(true), CompletableFuture.completedFuture(false));

        this.handler.onReceive(accepted);
        this.handler.onReceive(rejected);

        assertResponse(accepted, true, ResourceControlRequest.Status.ACCEPTED, "Server activated");
        assertResponse(rejected, false, ResourceControlRequest.Status.NOT_ALLOWED,
                "Server is stopping or draining");
    }

    @Test
    void shutdownReportsAcceptedAndRejectedOutcomes() {
        ResourceControlRequest accepted = request(ResourceControlRequest.Action.SHUTDOWN);
        ResourceControlRequest rejected = request(ResourceControlRequest.Action.SHUTDOWN);
        when(this.plugin.requestShutdown()).thenReturn(true, false);

        this.handler.onReceive(accepted);
        this.handler.onReceive(rejected);

        assertResponse(accepted, true, ResourceControlRequest.Status.ACCEPTED,
                "Server shutdown scheduled");
        assertResponse(rejected, false, ResourceControlRequest.Status.NOT_ALLOWED,
                "Server is already stopping");
    }

    @Test
    void synchronousLifecycleFailureIsReported() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.SHUTDOWN);
        when(this.plugin.requestShutdown()).thenThrow(new IllegalStateException("scheduler stopped"));

        this.handler.onReceive(request);

        assertResponse(request, false, ResourceControlRequest.Status.FAILED, "scheduler stopped");
    }

    @Test
    void synchronousLifecycleFailureWithoutMessageUsesItsType() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.REFRESH_LOAD);
        when(this.plugin.refreshLoad()).thenThrow(new IllegalStateException());

        this.handler.onReceive(request);

        assertResponse(request, false, ResourceControlRequest.Status.FAILED, "IllegalStateException");
    }

    @Test
    void asynchronousLifecycleFailureIsUnwrapped() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.ACTIVATE);
        when(this.plugin.activate()).thenReturn(CompletableFuture.failedFuture(
                new CompletionException(new IllegalStateException("registry offline"))));

        this.handler.onReceive(request);

        assertResponse(request, false, ResourceControlRequest.Status.FAILED, "registry offline");
    }

    @Test
    void asynchronousFailureWithoutCauseOrMessageUsesItsType() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.ACTIVATE);
        when(this.plugin.activate()).thenReturn(CompletableFuture.failedFuture(
                new CompletionException((Throwable) null)));

        this.handler.onReceive(request);

        assertResponse(request, false, ResourceControlRequest.Status.FAILED, "CompletionException");
    }

    @Test
    void timeoutFailureMapsToTimedOutWithoutWaiting() {
        TimeoutException timeout = new TimeoutException("operation deadline elapsed");
        CompletableFuture<Boolean> operation = new CompletableFuture<>() {
            @Override
            public CompletableFuture<Boolean> copy() {
                return CompletableFuture.failedFuture(timeout);
            }
        };
        ResourceControlRequest request = request(ResourceControlRequest.Action.ACTIVATE);
        when(this.plugin.activate()).thenReturn(operation);

        this.handler.onReceive(request);

        assertResponse(request, false, ResourceControlRequest.Status.TIMED_OUT,
                "operation deadline elapsed");
    }

    @Test
    void failedReplyFutureIsIgnoredAfterPublishingCorrelatedResponse() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.PING);
        EchoFuture<Void> failedPublication = new EchoFuture<>();
        failedPublication.completeExceptionally(new IllegalStateException("broker offline"));
        doReturn(failedPublication).when(request).reply(any(ResourceControlRequest.Response.class));

        assertThatCode(() -> this.handler.onReceive(request)).doesNotThrowAnyException();

        assertResponse(request, true, ResourceControlRequest.Status.ACCEPTED, "pong");
    }

    @Test
    void synchronousReplyFailureIsMappedToASecondFailedReply() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.PING);
        IllegalStateException failure = new IllegalStateException("broker unavailable");
        doThrow(failure).doReturn(EchoFuture.completed(null))
                .when(request).reply(any(ResourceControlRequest.Response.class));

        this.handler.onReceive(request);

        ArgumentCaptor<ResourceControlRequest.Response> responses =
                ArgumentCaptor.forClass(ResourceControlRequest.Response.class);
        verify(request, times(2)).reply(responses.capture());
        assertThat(responses.getAllValues().get(1).getMessageId()).isEqualTo(MESSAGE_ID);
        assertThat(responses.getAllValues().get(1).isAccepted()).isFalse();
        assertThat(responses.getAllValues().get(1).getStatus())
                .isEqualTo(ResourceControlRequest.Status.FAILED);
        assertThat(responses.getAllValues().get(1).getMessage()).isEqualTo("broker unavailable");
    }

    @Test
    void repeatedSynchronousReplyFailurePropagatesAfterActionFailureMapping() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.PING);
        IllegalStateException failure = new IllegalStateException("broker unavailable");
        doThrow(failure).when(request).reply(any(ResourceControlRequest.Response.class));

        assertThatThrownBy(() -> this.handler.onReceive(request)).isSameAs(failure);
        verify(request, times(2)).reply(any(ResourceControlRequest.Response.class));
    }

    @Test
    void repeatedCorrelationFailurePropagatesWithoutPublication() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.PING);
        IllegalStateException failure = new IllegalStateException("correlation unavailable");
        doThrow(failure).when(request).getMessageId();

        assertThatThrownBy(() -> this.handler.onReceive(request)).isSameAs(failure);
        verify(request, never()).reply(any(ResourceControlRequest.Response.class));
    }

    private static ResourceControlRequest request(ResourceControlRequest.Action action) {
        return request(new ResourceControlRequest(action, EchoResourceType.SERVER, "game-1", null));
    }

    private static ResourceControlRequest request(ResourceControlRequest.Action action,
                                                   EchoResourceType type, String id) {
        return request(new ResourceControlRequest(action, type, id, null));
    }

    private static ResourceControlRequest request(ResourceControlRequest.Action action, Duration deadline) {
        return request(new ResourceControlRequest(
                action, EchoResourceType.SERVER, "game-1", deadline, null));
    }

    private static ResourceControlRequest request(ResourceControlRequest.Action action, Instant deadline) {
        return request(new ResourceControlRequest(
                action, EchoResourceType.SERVER, "game-1", deadline, null));
    }

    private static ResourceControlRequest request(ResourceControlRequest request) {
        ResourceControlRequest spy = spy(request);
        spy.setExecutionDeadlineEpochMillis(NOW.plusSeconds(10).toEpochMilli());
        spy.setMessageId(MESSAGE_ID);
        doReturn(EchoFuture.completed(null)).when(spy).reply(any(ResourceControlRequest.Response.class));
        return spy;
    }

    private static ResourceControlRequest.Response response(ResourceControlRequest request) {
        ArgumentCaptor<ResourceControlRequest.Response> response =
                ArgumentCaptor.forClass(ResourceControlRequest.Response.class);
        verify(request).reply(response.capture());
        return response.getValue();
    }

    private static void assertResponse(ResourceControlRequest request, boolean accepted,
                                       ResourceControlRequest.Status status, String message) {
        ResourceControlRequest.Response response = response(request);
        assertThat(response.getMessageId()).isEqualTo(request.getMessageId());
        assertThat(response.isAccepted()).isEqualTo(accepted);
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getMessage()).isEqualTo(message);
    }
}
