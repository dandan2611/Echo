package fr.codinbox.echo.api.administration;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.exception.user.UserHasNoProxyException;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.MessageTarget;
import fr.codinbox.echo.api.messaging.impl.ResourceControlRequest;
import fr.codinbox.echo.api.messaging.impl.UserDisconnectRequest;
import fr.codinbox.echo.api.user.User;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Sends typed administration requests to one exact Echo resource. */
public final class RemoteAdministration {

    private final EchoClient echo;
    private final LongSupplier currentTimeMillis;

    public RemoteAdministration(@NotNull EchoClient echo) {
        this(echo, System::currentTimeMillis);
    }

    RemoteAdministration(EchoClient echo, LongSupplier currentTimeMillis) {
        this.echo = Objects.requireNonNull(echo, "echo");
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
    }

    public @NotNull EchoFuture<ResourceControlRequest.Response> control(
            @NotNull ResourceControlRequest request, @NotNull Duration timeout) {
        requireTimeout(timeout);
        Objects.requireNonNull(request, "request").setExecutionDeadlineEpochMillis(
                Math.addExact(this.currentTimeMillis.getAsLong(), timeout.toMillis()));
        String validationError = request.validationError();
        if (validationError != null)
            throw new IllegalArgumentException(validationError);
        request.setReplyTopic(this.echo.getLocalTopic());
        return this.echo.getMessagingProvider().request(
                this.topic(request.getExpectedResourceType(), request.getExpectedResourceId()),
                request, ResourceControlRequest.Response.class, timeout);
    }

    public @NotNull EchoFuture<UserDisconnectRequest.Response> disconnect(
            @NotNull User user, @NotNull String reason, @NotNull Duration timeout) {
        Objects.requireNonNull(user, "user");
        if (Objects.requireNonNull(reason, "reason").isBlank())
            throw new IllegalArgumentException("reason is required");
        requireTimeout(timeout);
        final long deadline = Math.addExact(this.currentTimeMillis.getAsLong(), timeout.toMillis());
        CompletableFuture<Map.Entry<String, String>> currentSession = user.getCurrentProxyId()
                .thenCombine(user.getSessionId(), (proxyId, sessionId) -> {
                    if (proxyId.isEmpty())
                        throw new UserHasNoProxyException(user.getId());
                    if (sessionId.isEmpty())
                        throw new IllegalStateException("User with id '%s' has no session".formatted(user.getId()));
                    return Map.entry(proxyId.get(), sessionId.get());
                });
        CompletableFuture<UserDisconnectRequest.Response> response = currentSession.copy()
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .thenCompose(session -> {
                    long remainingMillis = deadline - this.currentTimeMillis.getAsLong();
                    if (remainingMillis < 1)
                        return CompletableFuture.failedFuture(new TimeoutException(
                                "User disconnect deadline elapsed"));
                    UserDisconnectRequest request = new UserDisconnectRequest(
                            session.getKey(), session.getValue(), user.getId(), reason, deadline);
                    request.setReplyTopic(this.echo.getLocalTopic());
                    return this.echo.getMessagingProvider().request(
                            this.topic(EchoResourceType.PROXY, session.getKey()), request,
                            UserDisconnectRequest.Response.class, Duration.ofMillis(remainingMillis));
                });
        return EchoFuture.of(response);
    }

    private String topic(EchoResourceType type, String id) {
        MessageTarget.Builder builder = MessageTarget.builder();
        MessageTarget target = type == EchoResourceType.SERVER
                ? builder.withServer(id).build()
                : builder.withProxy(id).build();
        Set<String> topics = target.getTargets();
        if (topics.size() != 1)
            throw new IllegalStateException("Administration requests require exactly one target topic");
        return topics.iterator().next();
    }

    private static void requireTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.toMillis() < 1)
            throw new IllegalArgumentException("timeout must be at least 1ms");
    }
}
