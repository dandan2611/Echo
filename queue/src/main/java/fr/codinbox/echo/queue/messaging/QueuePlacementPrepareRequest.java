package fr.codinbox.echo.queue.messaging;

import fr.codinbox.echo.api.messaging.EchoMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Asks the allocated Paper server to accept a queue assignment before transfer. */
public final class QueuePlacementPrepareRequest extends EchoMessage {

    private UUID placementId;
    private long runVersion;
    private String queueId;
    private String serverId;
    private long preparationDeadlineEpochMillis;
    private Map<String, Set<UUID>> requests;

    public QueuePlacementPrepareRequest() {
    }

    public QueuePlacementPrepareRequest(@NotNull UUID placementId, long runVersion,
                                        @NotNull String queueId, @NotNull String serverId,
                                        long preparationDeadlineEpochMillis,
                                        @NotNull Map<String, Set<UUID>> requests) {
        this.placementId = placementId;
        this.runVersion = runVersion;
        this.queueId = queueId;
        this.serverId = serverId;
        this.preparationDeadlineEpochMillis = preparationDeadlineEpochMillis;
        this.requests = requests;
    }

    public UUID getPlacementId() { return this.placementId; }
    public void setPlacementId(UUID placementId) { this.placementId = placementId; }
    public long getRunVersion() { return this.runVersion; }
    public void setRunVersion(long runVersion) { this.runVersion = runVersion; }
    public String getQueueId() { return this.queueId; }
    public void setQueueId(String queueId) { this.queueId = queueId; }
    public String getServerId() { return this.serverId; }
    public void setServerId(String serverId) { this.serverId = serverId; }
    public long getPreparationDeadlineEpochMillis() { return this.preparationDeadlineEpochMillis; }
    public void setPreparationDeadlineEpochMillis(long preparationDeadlineEpochMillis) {
        this.preparationDeadlineEpochMillis = preparationDeadlineEpochMillis;
    }
    public Map<String, Set<UUID>> getRequests() { return this.requests; }
    public void setRequests(Map<String, Set<UUID>> requests) { this.requests = requests; }

    public static final class Response extends EchoMessage {

        private UUID placementId;
        private long runVersion;
        private boolean accepted;
        private String reason;

        public Response() {
        }

        public Response(@NotNull UUID placementId, long runVersion, boolean accepted, @Nullable String reason) {
            this.placementId = placementId;
            this.runVersion = runVersion;
            this.accepted = accepted;
            this.reason = reason;
        }

        public UUID getPlacementId() { return this.placementId; }
        public void setPlacementId(UUID placementId) { this.placementId = placementId; }
        public long getRunVersion() { return this.runVersion; }
        public void setRunVersion(long runVersion) { this.runVersion = runVersion; }
        public boolean isAccepted() { return this.accepted; }
        public void setAccepted(boolean accepted) { this.accepted = accepted; }
        public String getReason() { return this.reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
