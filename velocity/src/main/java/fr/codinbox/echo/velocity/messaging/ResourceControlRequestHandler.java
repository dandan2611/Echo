package fr.codinbox.echo.velocity.messaging;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.MessageHandler;
import fr.codinbox.echo.api.messaging.impl.ResourceControlRequest;
import fr.codinbox.echo.velocity.EchoPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Executes direct proxy administration requests on the plugin lifecycle. */
public final class ResourceControlRequestHandler implements MessageHandler<ResourceControlRequest> {

    private final EchoPlugin plugin;
    private final EchoClient echo;
    private final Clock clock;

    public ResourceControlRequestHandler(@NotNull EchoPlugin plugin, @NotNull EchoClient echo) {
        this(plugin, echo, Clock.systemUTC());
    }

    ResourceControlRequestHandler(EchoPlugin plugin, EchoClient echo, Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.echo = Objects.requireNonNull(echo, "echo");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onReceive(@NotNull ResourceControlRequest request) {
        String validationError = request.validationError();
        if (validationError != null) {
            this.reply(request, false, ResourceControlRequest.Status.INVALID_REQUEST, validationError);
            return;
        }
        if (request.getExpectedResourceType() != EchoResourceType.PROXY
                || this.echo.getCurrentResourceType() != EchoResourceType.PROXY
                || this.echo.getCurrentResourceId().filter(request.getExpectedResourceId()::equals).isEmpty()) {
            this.reply(request, false, ResourceControlRequest.Status.WRONG_TARGET,
                    "Request targets another resource");
            return;
        }

        final long now = this.clock.millis();
        if (now >= request.getExecutionDeadlineEpochMillis()) {
            this.reply(request, false, ResourceControlRequest.Status.EXPIRED, "Request deadline elapsed");
            return;
        }
        final long drainDeadline;
        try {
            drainDeadline = request.resolveDrainDeadlineEpochMillis(now);
        } catch (ArithmeticException error) {
            this.reply(request, false, ResourceControlRequest.Status.INVALID_REQUEST, "deadline is too large");
            return;
        }

        try {
            switch (request.getAction()) {
                case PING -> this.reply(request, true, ResourceControlRequest.Status.ACCEPTED, "pong");
                case REFRESH_LOAD -> this.reply(request, false,
                        ResourceControlRequest.Status.UNSUPPORTED_ACTION,
                        "Proxy resources do not publish server load");
                case DRAIN -> {
                    boolean accepted = drainDeadline > 0
                            ? this.plugin.beginDrain(Instant.ofEpochMilli(drainDeadline))
                            : this.plugin.beginDrain();
                    this.reply(request, accepted,
                            accepted ? ResourceControlRequest.Status.ACCEPTED
                                    : ResourceControlRequest.Status.NOT_ALLOWED,
                            accepted ? "Proxy drain started" : "Proxy is stopping or draining");
                }
                case ACTIVATE -> {
                    boolean accepted = this.plugin.activate();
                    this.reply(request, accepted,
                            accepted ? ResourceControlRequest.Status.ACCEPTED
                                    : ResourceControlRequest.Status.NOT_ALLOWED,
                            accepted ? "Proxy activated" : "Proxy is stopping or draining");
                }
                case SHUTDOWN -> {
                    boolean accepted = this.plugin.requestShutdown();
                    this.reply(request, accepted,
                            accepted ? ResourceControlRequest.Status.ACCEPTED
                                    : ResourceControlRequest.Status.NOT_ALLOWED,
                            accepted ? "Proxy shutdown scheduled" : "Proxy is already stopping");
                }
            }
        } catch (RuntimeException error) {
            this.reply(request, false, ResourceControlRequest.Status.FAILED,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    private void reply(ResourceControlRequest request, boolean accepted,
                       ResourceControlRequest.Status status, String message) {
        request.reply(new ResourceControlRequest.Response(request, accepted, status, message));
    }
}
