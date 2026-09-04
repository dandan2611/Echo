package fr.codinbox.echo.paper.messaging;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.MessageHandler;
import fr.codinbox.echo.api.messaging.impl.ResourceControlRequest;
import fr.codinbox.echo.paper.EchoPaper;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Executes direct server administration requests without blocking the messaging thread. */
public final class ResourceControlRequestHandler implements MessageHandler<ResourceControlRequest> {

    private final EchoPaper plugin;
    private final EchoClient echo;
    private final Clock clock;

    public ResourceControlRequestHandler(@NotNull EchoPaper plugin, @NotNull EchoClient echo) {
        this(plugin, echo, Clock.systemUTC());
    }

    ResourceControlRequestHandler(EchoPaper plugin, EchoClient echo, Clock clock) {
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
        if (request.getExpectedResourceType() != EchoResourceType.SERVER
                || this.echo.getCurrentResourceType() != EchoResourceType.SERVER
                || this.echo.getCurrentResourceId().filter(request.getExpectedResourceId()::equals).isEmpty()) {
            this.reply(request, false, ResourceControlRequest.Status.WRONG_TARGET,
                    "Request targets another resource");
            return;
        }

        final long now = this.clock.millis();
        final long executionDeadline = request.getExecutionDeadlineEpochMillis();
        if (now >= executionDeadline) {
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
                case REFRESH_LOAD -> this.complete(request,
                        this.plugin.refreshLoad().thenApply(ignored -> true), executionDeadline,
                        "Server load refreshed", "Server load refresh rejected");
                case DRAIN -> this.complete(request,
                        (drainDeadline > 0
                                ? this.plugin.beginDrain(Instant.ofEpochMilli(drainDeadline))
                                : this.plugin.beginDrain()).thenApply(ignored -> true),
                        executionDeadline, "Server drain started", "Server drain rejected");
                case ACTIVATE -> this.complete(request, this.plugin.activate(), executionDeadline,
                        "Server activated", "Server is stopping or draining");
                case SHUTDOWN -> {
                    boolean accepted = this.plugin.requestShutdown();
                    this.reply(request, accepted,
                            accepted ? ResourceControlRequest.Status.ACCEPTED
                                    : ResourceControlRequest.Status.NOT_ALLOWED,
                            accepted ? "Server shutdown scheduled" : "Server is already stopping");
                }
            }
        } catch (RuntimeException error) {
            this.reply(request, false, ResourceControlRequest.Status.FAILED,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    private void complete(ResourceControlRequest request, CompletableFuture<Boolean> operation,
                          long deadline, String acceptedMessage, String rejectedMessage) {
        operation.copy().orTimeout(Math.max(1L, deadline - this.clock.millis()), TimeUnit.MILLISECONDS)
                .whenComplete((accepted, error) -> {
                    if (error == null) {
                        this.reply(request, Boolean.TRUE.equals(accepted),
                                Boolean.TRUE.equals(accepted)
                                        ? ResourceControlRequest.Status.ACCEPTED
                                        : ResourceControlRequest.Status.NOT_ALLOWED,
                                Boolean.TRUE.equals(accepted) ? acceptedMessage : rejectedMessage);
                        return;
                    }
                    Throwable cause = error instanceof CompletionException && error.getCause() != null
                            ? error.getCause() : error;
                    this.reply(request, false,
                            cause instanceof TimeoutException
                                    ? ResourceControlRequest.Status.TIMED_OUT
                                    : ResourceControlRequest.Status.FAILED,
                            cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
                });
    }

    private void reply(ResourceControlRequest request, boolean accepted,
                       ResourceControlRequest.Status status, String message) {
        request.reply(new ResourceControlRequest.Response(request, accepted, status, message));
    }
}
