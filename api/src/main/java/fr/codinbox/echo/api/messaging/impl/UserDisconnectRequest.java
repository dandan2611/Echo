package fr.codinbox.echo.api.messaging.impl;

import fr.codinbox.echo.api.messaging.EchoMessage;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/** A bounded request to disconnect one player from their current proxy. */
@NoArgsConstructor
@Getter
@Setter
public final class UserDisconnectRequest extends EchoMessage {

    private @NotNull String expectedProxyId;
    private @NotNull String expectedSessionId;
    private @NotNull UUID userId;
    private @NotNull String reason;
    private long deadlineEpochMillis;

    public UserDisconnectRequest(@NotNull String expectedProxyId, @NotNull UUID userId,
                                 @NotNull String reason, long deadlineEpochMillis) {
        this.expectedProxyId = requireText(expectedProxyId, "expectedProxyId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.reason = requireText(reason, "reason");
        if (deadlineEpochMillis < 1)
            throw new IllegalArgumentException("deadline must be positive");
        this.deadlineEpochMillis = deadlineEpochMillis;
    }

    public UserDisconnectRequest(@NotNull String expectedProxyId, @NotNull String expectedSessionId,
                                 @NotNull UUID userId, @NotNull String reason, long deadlineEpochMillis) {
        this(expectedProxyId, userId, reason, deadlineEpochMillis);
        this.expectedSessionId = requireText(expectedSessionId, "expectedSessionId");
    }

    /** Returns null when fields received from Jackson form a valid request. */
    public @Nullable String validationError() {
        if (this.expectedProxyId == null || this.expectedProxyId.isBlank())
            return "expected proxy id is required";
        if (this.expectedSessionId == null || this.expectedSessionId.isBlank())
            return "expected session id is required";
        if (this.userId == null)
            return "user id is required";
        if (this.reason == null || this.reason.isBlank())
            return "reason is required";
        if (this.deadlineEpochMillis < 1)
            return "deadline must be positive";
        return null;
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank())
            throw new IllegalArgumentException(name + " is required");
        return value;
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static final class Response extends EchoMessage {

        private boolean accepted;
        private @NotNull Status status;
        private @NotNull String message;

        public Response(@NotNull UserDisconnectRequest request, boolean accepted,
                        @NotNull Status status, @NotNull String message) {
            this.accepted = accepted;
            this.status = Objects.requireNonNull(status, "status");
            this.message = Objects.requireNonNull(message, "message");
            this.setMessageId(Objects.requireNonNull(request, "request").getMessageId());
        }
    }

    public enum Status {
        DISCONNECTED,
        PLAYER_NOT_FOUND,
        INVALID_REQUEST,
        WRONG_TARGET,
        EXPIRED,
        TIMED_OUT,
        FAILED
    }
}
