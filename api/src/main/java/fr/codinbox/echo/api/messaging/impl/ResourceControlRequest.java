package fr.codinbox.echo.api.messaging.impl;

import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.EchoMessage;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** A bounded request to administer one exact Echo server or proxy. */
@NoArgsConstructor
@Getter
@Setter
public final class ResourceControlRequest extends EchoMessage {

    private @NotNull Action action;
    private @NotNull EchoResourceType expectedResourceType;
    private @NotNull String expectedResourceId;
    private long executionDeadlineEpochMillis;
    private long deadlineDurationMillis;
    private long deadlineEpochMillis;
    private @Nullable String reason;

    public ResourceControlRequest(@NotNull Action action, @NotNull EchoResourceType expectedResourceType,
                                  @NotNull String expectedResourceId, @Nullable String reason) {
        this.action = Objects.requireNonNull(action, "action");
        this.expectedResourceType = Objects.requireNonNull(expectedResourceType, "expectedResourceType");
        this.expectedResourceId = requireId(expectedResourceId);
        this.reason = reason;
    }

    public ResourceControlRequest(@NotNull Action action, @NotNull EchoResourceType expectedResourceType,
                                  @NotNull String expectedResourceId, @NotNull Duration deadline,
                                  @Nullable String reason) {
        this(action, expectedResourceType, expectedResourceId, reason);
        if (deadline.isZero() || deadline.isNegative())
            throw new IllegalArgumentException("deadline duration must be positive");
        this.deadlineDurationMillis = deadline.toMillis();
        if (this.deadlineDurationMillis < 1)
            throw new IllegalArgumentException("deadline duration must be at least 1ms");
    }

    public ResourceControlRequest(@NotNull Action action, @NotNull EchoResourceType expectedResourceType,
                                  @NotNull String expectedResourceId, @NotNull Instant deadline,
                                  @Nullable String reason) {
        this(action, expectedResourceType, expectedResourceId, reason);
        this.deadlineEpochMillis = Objects.requireNonNull(deadline, "deadline").toEpochMilli();
        if (this.deadlineEpochMillis < 1)
            throw new IllegalArgumentException("absolute deadline must be positive");
    }

    /** Returns null when fields received from Jackson form a valid request. */
    public @Nullable String validationError() {
        if (this.action == null)
            return "action is required";
        if (this.expectedResourceType == null)
            return "expected resource type is required";
        if (this.expectedResourceId == null || this.expectedResourceId.isBlank())
            return "expected resource id is required";
        if (this.executionDeadlineEpochMillis < 1)
            return "execution deadline must be positive";
        if (this.deadlineDurationMillis < 0 || this.deadlineEpochMillis < 0)
            return "deadline must be positive";
        if (this.deadlineDurationMillis > 0 && this.deadlineEpochMillis > 0)
            return "deadline duration and absolute deadline are mutually exclusive";
        return null;
    }

    /** Resolves a relative deadline against receiver time; zero means no deadline. */
    public long resolveDrainDeadlineEpochMillis(long nowEpochMillis) {
        if (this.deadlineEpochMillis > 0)
            return this.deadlineEpochMillis;
        return this.deadlineDurationMillis > 0
                ? Math.addExact(nowEpochMillis, this.deadlineDurationMillis)
                : 0;
    }

    private static String requireId(String id) {
        if (Objects.requireNonNull(id, "expectedResourceId").isBlank())
            throw new IllegalArgumentException("expected resource id is required");
        return id;
    }

    public enum Action {
        PING,
        REFRESH_LOAD,
        DRAIN,
        ACTIVATE,
        SHUTDOWN
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static final class Response extends EchoMessage {

        private boolean accepted;
        private @NotNull Status status;
        private @NotNull String message;

        public Response(@NotNull ResourceControlRequest request, boolean accepted,
                        @NotNull Status status, @NotNull String message) {
            this.accepted = accepted;
            this.status = Objects.requireNonNull(status, "status");
            this.message = Objects.requireNonNull(message, "message");
            this.setMessageId(Objects.requireNonNull(request, "request").getMessageId());
        }
    }

    public enum Status {
        ACCEPTED,
        INVALID_REQUEST,
        WRONG_TARGET,
        EXPIRED,
        NOT_ALLOWED,
        UNSUPPORTED_ACTION,
        TIMED_OUT,
        FAILED
    }
}
