package fr.codinbox.echo.velocity.messaging;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.impl.ResourceControlRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
class ResourceControlRequestHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private EchoPlugin plugin;
    private EchoClient echo;
    private ResourceControlRequestHandler handler;

    @BeforeEach
    void setUp() {
        this.plugin = mock(EchoPlugin.class);
        this.echo = mock(EchoClient.class);
        this.handler = new ResourceControlRequestHandler(
                this.plugin, this.echo, Clock.fixed(NOW, ZoneOffset.UTC));
        when(this.echo.getCurrentResourceType()).thenReturn(EchoResourceType.PROXY);
        when(this.echo.getCurrentResourceId()).thenReturn(Optional.of("proxy-1"));
    }

    @Test
    void invalidRequestIsRejectedBeforeTargetOrLifecycleWork() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.SHUTDOWN);
        when(request.validationError()).thenReturn("action is required");

        this.handler.onReceive(request);

        assertResponse(request, false, ResourceControlRequest.Status.INVALID_REQUEST);
        verifyNoInteractions(this.echo, this.plugin);
    }

    @Test
    void wrongResourceTypeIsRejectedWithoutLifecycleWork() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.SHUTDOWN);
        when(request.getExpectedResourceType()).thenReturn(EchoResourceType.SERVER);

        this.handler.onReceive(request);

        assertResponse(request, false, ResourceControlRequest.Status.WRONG_TARGET);
        verifyNoInteractions(this.plugin);
    }

    @Test
    void wrongLocalTypeAndMissingOrWrongLocalIdAreRejected() {
        ResourceControlRequest wrongType = request(ResourceControlRequest.Action.SHUTDOWN);
        when(this.echo.getCurrentResourceType()).thenReturn(EchoResourceType.SERVER);

        this.handler.onReceive(wrongType);

        when(this.echo.getCurrentResourceType()).thenReturn(EchoResourceType.PROXY);
        ResourceControlRequest missingId = request(ResourceControlRequest.Action.SHUTDOWN);
        when(this.echo.getCurrentResourceId()).thenReturn(Optional.empty());

        this.handler.onReceive(missingId);

        ResourceControlRequest wrongId = request(ResourceControlRequest.Action.SHUTDOWN);
        when(this.echo.getCurrentResourceId()).thenReturn(Optional.of("proxy-2"));

        this.handler.onReceive(wrongId);

        assertResponse(wrongType, false, ResourceControlRequest.Status.WRONG_TARGET);
        assertResponse(missingId, false, ResourceControlRequest.Status.WRONG_TARGET);
        assertResponse(wrongId, false, ResourceControlRequest.Status.WRONG_TARGET);
        verifyNoInteractions(this.plugin);
    }

    @Test
    void pingIsCorrelated() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.PING);

        this.handler.onReceive(request);

        ResourceControlRequest.Response response = response(request);
        assertThat(response.getMessageId()).isEqualTo(request.getMessageId());
        assertThat(response.isAccepted()).isTrue();
        assertThat(response.getMessage()).isEqualTo("pong");
    }

    @Test
    void expiredExecutionDeadlineStopsShutdownAndActivationBeforeLifecycleWork() {
        ResourceControlRequest shutdown = request(ResourceControlRequest.Action.SHUTDOWN);
        ResourceControlRequest activate = request(ResourceControlRequest.Action.ACTIVATE);
        when(shutdown.getExecutionDeadlineEpochMillis()).thenReturn(NOW.toEpochMilli());
        when(activate.getExecutionDeadlineEpochMillis()).thenReturn(NOW.toEpochMilli() - 1);

        this.handler.onReceive(shutdown);
        this.handler.onReceive(activate);

        assertResponse(shutdown, false, ResourceControlRequest.Status.EXPIRED);
        assertResponse(activate, false, ResourceControlRequest.Status.EXPIRED);
        verifyNoInteractions(this.plugin);
    }

    @Test
    void overflowingRelativeDeadlineIsInvalid() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.DRAIN);
        when(request.resolveDrainDeadlineEpochMillis(NOW.toEpochMilli())).thenThrow(new ArithmeticException());

        this.handler.onReceive(request);

        ResourceControlRequest.Response response = assertResponse(
                request, false, ResourceControlRequest.Status.INVALID_REQUEST);
        assertThat(response.getMessage()).isEqualTo("deadline is too large");
        verifyNoInteractions(this.plugin);
    }

    @Test
    void drainUsesTheRequestedAbsoluteDeadline() {
        Instant deadline = NOW.plusSeconds(20);
        ResourceControlRequest request = request(ResourceControlRequest.Action.DRAIN);
        when(request.resolveDrainDeadlineEpochMillis(NOW.toEpochMilli())).thenReturn(deadline.toEpochMilli());
        when(this.plugin.beginDrain(deadline)).thenReturn(true);

        this.handler.onReceive(request);

        verify(this.plugin).beginDrain(deadline);
        assertResponse(request, true, ResourceControlRequest.Status.ACCEPTED);
    }

    @Test
    void drainUsesTheResolvedRelativeDeadlineAndReportsRejection() {
        Instant deadline = NOW.plusSeconds(5);
        ResourceControlRequest request = request(ResourceControlRequest.Action.DRAIN);
        when(request.resolveDrainDeadlineEpochMillis(NOW.toEpochMilli())).thenReturn(deadline.toEpochMilli());
        when(this.plugin.beginDrain(deadline)).thenReturn(false);

        this.handler.onReceive(request);

        verify(this.plugin).beginDrain(deadline);
        assertResponse(request, false, ResourceControlRequest.Status.NOT_ALLOWED);
    }

    @Test
    void drainWithoutDeadlineUsesTheUnboundedLifecycleMethod() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.DRAIN);
        when(this.plugin.beginDrain()).thenReturn(true);

        this.handler.onReceive(request);

        verify(this.plugin).beginDrain();
        assertResponse(request, true, ResourceControlRequest.Status.ACCEPTED);
    }

    @Test
    void refreshIsUnsupportedAndUnsafeActivationIsRejected() {
        ResourceControlRequest refresh = request(ResourceControlRequest.Action.REFRESH_LOAD);
        ResourceControlRequest activate = request(ResourceControlRequest.Action.ACTIVATE);
        when(this.plugin.activate()).thenReturn(false);

        this.handler.onReceive(refresh);
        this.handler.onReceive(activate);

        assertResponse(refresh, false, ResourceControlRequest.Status.UNSUPPORTED_ACTION);
        assertResponse(activate, false, ResourceControlRequest.Status.NOT_ALLOWED);
    }

    @Test
    void activationCanBeAccepted() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.ACTIVATE);
        when(this.plugin.activate()).thenReturn(true);

        this.handler.onReceive(request);

        verify(this.plugin).activate();
        assertResponse(request, true, ResourceControlRequest.Status.ACCEPTED);
    }

    @Test
    void shutdownDelegatesToTheSafePluginSchedulerBridge() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.SHUTDOWN);
        when(this.plugin.requestShutdown()).thenReturn(true);

        this.handler.onReceive(request);

        verify(this.plugin).requestShutdown();
        assertResponse(request, true, ResourceControlRequest.Status.ACCEPTED);
    }

    @Test
    void duplicateShutdownIsRejected() {
        ResourceControlRequest request = request(ResourceControlRequest.Action.SHUTDOWN);
        when(this.plugin.requestShutdown()).thenReturn(false);

        this.handler.onReceive(request);

        assertResponse(request, false, ResourceControlRequest.Status.NOT_ALLOWED);
    }

    @Test
    void synchronousLifecycleFailuresAreReportedWithOrWithoutMessages() {
        ResourceControlRequest activate = request(ResourceControlRequest.Action.ACTIVATE);
        when(this.plugin.activate()).thenThrow(new IllegalStateException("activation failed"));

        this.handler.onReceive(activate);

        ResourceControlRequest.Response activateResponse = assertResponse(
                activate, false, ResourceControlRequest.Status.FAILED);
        assertThat(activateResponse.getMessage()).isEqualTo("activation failed");

        ResourceControlRequest shutdown = request(ResourceControlRequest.Action.SHUTDOWN);
        when(this.plugin.requestShutdown()).thenThrow(new IllegalStateException());

        this.handler.onReceive(shutdown);

        ResourceControlRequest.Response shutdownResponse = assertResponse(
                shutdown, false, ResourceControlRequest.Status.FAILED);
        assertThat(shutdownResponse.getMessage()).isEqualTo("IllegalStateException");
    }

    private static ResourceControlRequest request(ResourceControlRequest.Action action) {
        ResourceControlRequest request = mock(ResourceControlRequest.class);
        when(request.validationError()).thenReturn(null);
        when(request.getAction()).thenReturn(action);
        when(request.getExpectedResourceType()).thenReturn(EchoResourceType.PROXY);
        when(request.getExpectedResourceId()).thenReturn("proxy-1");
        when(request.getExecutionDeadlineEpochMillis()).thenReturn(NOW.plusSeconds(10).toEpochMilli());
        when(request.getMessageId()).thenReturn(REQUEST_ID);
        return request;
    }

    private static ResourceControlRequest.Response response(ResourceControlRequest request) {
        ArgumentCaptor<ResourceControlRequest.Response> response =
                ArgumentCaptor.forClass(ResourceControlRequest.Response.class);
        verify(request).reply(response.capture());
        return response.getValue();
    }

    private static ResourceControlRequest.Response assertResponse(
            ResourceControlRequest request, boolean accepted, ResourceControlRequest.Status status) {
        ResourceControlRequest.Response response = response(request);
        assertThat(response.isAccepted()).isEqualTo(accepted);
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getMessage()).isNotBlank();
        return response;
    }
}
