package fr.codinbox.echo.velocity.messaging;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.MessageHandler;
import fr.codinbox.echo.api.messaging.impl.UserDisconnectRequest;
import fr.codinbox.echo.velocity.EchoPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Disconnects one exact player on the Velocity scheduler. */
public final class UserDisconnectRequestHandler implements MessageHandler<UserDisconnectRequest> {

    private final EchoPlugin plugin;
    private final EchoClient echo;
    private final Clock clock;

    public UserDisconnectRequestHandler(@NotNull EchoPlugin plugin, @NotNull EchoClient echo) {
        this(plugin, echo, Clock.systemUTC());
    }

    UserDisconnectRequestHandler(EchoPlugin plugin, EchoClient echo, Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.echo = Objects.requireNonNull(echo, "echo");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onReceive(@NotNull UserDisconnectRequest request) {
        String validationError = request.validationError();
        if (validationError != null) {
            this.reply(request, false, UserDisconnectRequest.Status.INVALID_REQUEST, validationError);
            return;
        }
        if (this.echo.getCurrentResourceType() != EchoResourceType.PROXY
                || this.echo.getCurrentResourceId().filter(request.getExpectedProxyId()::equals).isEmpty()) {
            this.reply(request, false, UserDisconnectRequest.Status.WRONG_TARGET,
                    "Request targets another proxy");
            return;
        }
        long remainingMillis = request.getDeadlineEpochMillis() - this.clock.millis();
        if (remainingMillis < 1) {
            this.reply(request, false, UserDisconnectRequest.Status.EXPIRED,
                    "Request deadline elapsed");
            return;
        }

        try {
            CompletableFuture<Boolean> disconnect = this.echo.getUserById(request.getUserId())
                    .thenCompose(user -> user.map(value -> value.getSessionId().thenCompose(sessionId ->
                            sessionId.filter(request.getExpectedSessionId()::equals).isPresent()
                                    ? this.plugin.disconnectPlayer(request.getUserId(), request.getReason(),
                                    request.getExpectedSessionId(), request.getDeadlineEpochMillis()).copy()
                                    .orTimeout(remainingMillis, TimeUnit.MILLISECONDS)
                                    : CompletableFuture.completedFuture(false)))
                            .orElseGet(() -> CompletableFuture.completedFuture(false)));
            disconnect.copy()
                    .orTimeout(remainingMillis, TimeUnit.MILLISECONDS)
                    .whenComplete((disconnected, error) -> {
                        if (error == null) {
                            this.reply(request, Boolean.TRUE.equals(disconnected),
                                    Boolean.TRUE.equals(disconnected)
                                            ? UserDisconnectRequest.Status.DISCONNECTED
                                            : UserDisconnectRequest.Status.PLAYER_NOT_FOUND,
                                    Boolean.TRUE.equals(disconnected)
                                            ? "Player disconnected" : "Player is not connected to this proxy");
                            return;
                        }
                        Throwable cause = error instanceof CompletionException && error.getCause() != null
                                ? error.getCause() : error;
                        this.reply(request, false,
                                cause instanceof TimeoutException
                                        ? UserDisconnectRequest.Status.TIMED_OUT
                                        : UserDisconnectRequest.Status.FAILED,
                                cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
                    });
        } catch (RuntimeException error) {
            this.reply(request, false, UserDisconnectRequest.Status.FAILED,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    private void reply(UserDisconnectRequest request, boolean accepted,
                       UserDisconnectRequest.Status status, String message) {
        request.reply(new UserDisconnectRequest.Response(request, accepted, status, message));
    }
}
