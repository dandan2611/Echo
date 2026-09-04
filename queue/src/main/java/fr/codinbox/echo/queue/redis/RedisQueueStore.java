package fr.codinbox.echo.queue.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import fr.codinbox.echo.queue.QueueAdministration;
import fr.codinbox.echo.queue.QueueDefinition;
import fr.codinbox.echo.queue.QueueId;
import fr.codinbox.echo.queue.QueueRequest;
import fr.codinbox.echo.queue.QueueRequestStatus;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class RedisQueueStore implements QueueStore {

    private final RedissonClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;

    RedisQueueStore(@NotNull RedisConnection connection) {
        this(connection, Clock.systemUTC());
    }

    RedisQueueStore(@NotNull RedisConnection connection, @NotNull Clock clock) {
        this.client = connection.getClient();
        this.clock = clock;
    }

    @Override
    public QueueRequestStatus enqueue(QueueDefinition definition, QueueRequest request) {
        return this.locked(definition.id(), () -> {
            QueueState state = this.read(definition.id());
            StoredRequest existing = state.requests().get(request.requestId());
            if (existing != null) {
                if (!existing.samePayload(request))
                    throw new IllegalStateException("Queue request ID has a different payload: " + request.requestId());
                return existing.toStatus();
            }
            for (StoredRequest active : state.requests().values()) {
                if (!active.terminal() && active.members().stream().anyMatch(request.members()::contains))
                    throw new IllegalStateException("A member already has an active request in this queue");
            }

            StoredRequest added = StoredRequest.queued(request, state.nextSequence(), this.clock.millis());
            Map<String, StoredRequest> requests = new LinkedHashMap<>(state.requests());
            requests.put(request.requestId(), added);
            RunRecord run = state.run() == null ? RunRecord.allocating() : state.run();
            this.write(definition.id(), state.withContent(state.nextSequence() + 1, requests, run));
            return added.toStatus();
        });
    }

    @Override
    public Optional<QueueRequestStatus> get(QueueId queueId, String requestId) {
        return Optional.ofNullable(this.read(queueId).requests().get(requestId)).map(StoredRequest::toStatus);
    }

    @Override
    public boolean cancel(QueueId queueId, String requestId) {
        return this.locked(queueId, () -> {
            QueueState state = this.read(queueId);
            StoredRequest request = state.requests().get(requestId);
            if (request == null || request.state() != QueueRequestStatus.State.QUEUED)
                return false;
            Map<String, StoredRequest> requests = new LinkedHashMap<>(state.requests());
            requests.put(requestId, request.withState(QueueRequestStatus.State.CANCELLED,
                    null, null, Map.of(), null).withUpdatedAt(this.clock.millis()));
            this.write(queueId, state.withContent(state.nextSequence(), requests, state.run()));
            return true;
        });
    }

    @Override
    public QueueSnapshot snapshot(QueueId queueId) {
        QueueState state = this.read(queueId);
        return new QueueSnapshot(state.paused(), state.pauseReason(),
                List.copyOf(state.requests().values()), state.run());
    }

    @Override
    public boolean paused(QueueId queueId) {
        return this.read(queueId).paused();
    }

    @Override
    public boolean pause(QueueId queueId, String reason) {
        return this.locked(queueId, () -> {
            QueueState state = this.read(queueId);
            if (state.paused() && reason.equals(state.pauseReason()))
                return false;
            this.write(queueId, state.withPause(true, reason));
            return true;
        });
    }

    @Override
    public boolean resume(QueueId queueId) {
        return this.locked(queueId, () -> {
            QueueState state = this.read(queueId);
            if (!state.paused())
                return false;
            this.write(queueId, state.withPause(false, null));
            return true;
        });
    }

    @Override
    public boolean retry(QueueId queueId, String requestId) {
        return this.locked(queueId, () -> {
            QueueState state = this.read(queueId);
            StoredRequest request = state.requests().get(requestId);
            if (request == null || request.state() != QueueRequestStatus.State.FAILED
                    && request.state() != QueueRequestStatus.State.CANCELLED)
                return false;
            if (state.run() != null && requestId.equals(state.run().activeRequestId()))
                return false;
            for (StoredRequest active : state.requests().values()) {
                if (active != request && !active.terminal()
                        && active.members().stream().anyMatch(request.members()::contains))
                    throw new IllegalStateException("A member already has an active request in this queue");
            }

            Map<String, StoredRequest> requests = new LinkedHashMap<>(state.requests());
            requests.put(requestId, request.requeued(state.nextSequence(), this.clock.millis()));
            RunRecord run = state.run() == null ? RunRecord.allocating() : state.run();
            this.write(queueId, state.withContent(state.nextSequence() + 1, requests, run));
            return true;
        });
    }

    @Override
    public int purgeTerminal(QueueId queueId, Instant cutoff) {
        return this.locked(queueId, () -> {
            QueueState state = this.read(queueId);
            String activeRequestId = state.run() == null ? null : state.run().activeRequestId();
            Map<String, StoredRequest> requests = new LinkedHashMap<>(state.requests());
            int before = requests.size();
            requests.values().removeIf(request -> request.terminal()
                    && request.updatedAtEpochMillis() != null
                    && request.updatedAtEpochMillis() < cutoff.toEpochMilli()
                    && !request.requestId().equals(activeRequestId));
            int removed = before - requests.size();
            if (removed > 0)
                this.write(queueId, state.withContent(state.nextSequence(), requests, state.run()));
            return removed;
        });
    }

    @Override
    public Optional<QueueClaim> claim(QueueDefinition definition, String workerId, Duration ttl) {
        String token = workerId + ":" + UUID.randomUUID();
        return this.locked(definition.id(), () -> {
            QueueState state = this.read(definition.id());
            if (state.paused())
                return Optional.empty();
            RBucket<String> claim = this.bucket(definition.id(), "claim");
            if (!claim.trySet(token, ttl.toMillis(), TimeUnit.MILLISECONDS))
                return Optional.empty();
            try {
                boolean queued = state.requests().values().stream()
                        .anyMatch(request -> request.state() == QueueRequestStatus.State.QUEUED);
                RunRecord run = state.run();
                if (run == null && queued) {
                    run = RunRecord.allocating();
                    state = state.withContent(state.nextSequence(), state.requests(), run);
                    this.write(definition.id(), state);
                }
                boolean freshUnusedServer = run != null && run.state() == RunState.READY && !run.handedOff();
                if (run == null || (run.state() == RunState.READY && !queued && !freshUnusedServer)) {
                    this.deleteClaim(definition.id(), token);
                    return Optional.empty();
                }
                return Optional.of(new QueueClaim(definition.id(), token, run,
                        List.copyOf(state.requests().values())));
            } catch (RuntimeException error) {
                this.deleteClaim(definition.id(), token);
                throw error;
            }
        });
    }

    @Override
    public Optional<QueueClaim> commit(QueueClaim expected, RunRecord next,
                                       Collection<StoredRequest> updates) {
        return this.locked(expected.queueId(), () -> {
            if (!expected.token().equals(this.bucket(expected.queueId(), "claim").get()))
                return Optional.empty();
            QueueState state = this.read(expected.queueId());
            RunRecord current = state.run();
            if (current == null || current.revision() != expected.run().revision()
                    || !current.placementId().equals(expected.run().placementId()))
                return Optional.empty();
            if (next != null && next.revision() != current.revision() + 1)
                throw new IllegalArgumentException("The next run revision must increment by one");

            Map<String, StoredRequest> requests = new LinkedHashMap<>(state.requests());
            long updatedAt = this.clock.millis();
            for (StoredRequest update : updates) {
                StoredRequest previous = requests.get(update.requestId());
                if (previous == null || update.version() != previous.version() + 1)
                    throw new IllegalArgumentException("The request version must increment by one: "
                            + update.requestId());
                requests.put(update.requestId(), update.withUpdatedAt(updatedAt));
            }
            this.write(expected.queueId(), state.withContent(state.nextSequence(), requests, next));
            return Optional.of(new QueueClaim(expected.queueId(), expected.token(), next,
                    List.copyOf(requests.values())));
        });
    }

    @Override
    public boolean renew(QueueClaim expected, Duration ttl) {
        return this.locked(expected.queueId(), () -> {
            RBucket<String> claim = this.bucket(expected.queueId(), "claim");
            return expected.token().equals(claim.get())
                    && claim.expire(ttl.toMillis(), TimeUnit.MILLISECONDS);
        });
    }

    @Override
    public void release(QueueClaim claim) {
        this.locked(claim.queueId(), () -> {
            this.deleteClaim(claim.queueId(), claim.token());
            return null;
        });
    }

    @Override
    public void close() {
    }

    private QueueState read(QueueId queueId) {
        String value = this.<String>bucket(queueId, "state").get();
        if (value == null)
            return QueueState.empty();
        try {
            QueueState state = this.mapper.readValue(value, QueueState.class);
            if (state.formatVersion() != 1)
                throw new IllegalStateException("Unsupported queue state format: " + state.formatVersion());
            return state;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Corrupt queue state for " + queueId, error);
        }
    }

    private void write(QueueId queueId, QueueState state) {
        try {
            this.<String>bucket(queueId, "state").set(this.mapper.writeValueAsString(state));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to encode queue state for " + queueId, error);
        }
    }

    private void deleteClaim(QueueId queueId, String token) {
        RBucket<String> claim = this.bucket(queueId, "claim");
        if (token.equals(claim.get()))
            claim.delete();
    }

    private <T> RBucket<T> bucket(QueueId queueId, String suffix) {
        return this.client.getBucket("echo:queue:{" + hash(queueId.value()) + "}:" + suffix);
    }

    private <T> T locked(QueueId queueId, Supplier<T> action) {
        RLock lock = this.client.getLock("echo:queue:{" + hash(queueId.value()) + "}:lock");
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

interface QueueStore extends AutoCloseable {
    QueueRequestStatus enqueue(QueueDefinition definition, QueueRequest request);
    Optional<QueueRequestStatus> get(QueueId queueId, String requestId);
    boolean cancel(QueueId queueId, String requestId);
    QueueSnapshot snapshot(QueueId queueId);
    boolean paused(QueueId queueId);
    boolean pause(QueueId queueId, String reason);
    boolean resume(QueueId queueId);
    boolean retry(QueueId queueId, String requestId);
    int purgeTerminal(QueueId queueId, Instant cutoff);
    Optional<QueueClaim> claim(QueueDefinition definition, String workerId, Duration ttl);
    Optional<QueueClaim> commit(QueueClaim expected, RunRecord next, Collection<StoredRequest> updates);
    boolean renew(QueueClaim expected, Duration ttl);
    void release(QueueClaim claim);
    @Override void close();
}

record QueueState(int formatVersion, long nextSequence, Map<String, StoredRequest> requests, RunRecord run,
                  boolean paused, String pauseReason) {
    QueueState(int formatVersion, long nextSequence, Map<String, StoredRequest> requests, RunRecord run) {
        this(formatVersion, nextSequence, requests, run, false, null);
    }

    static QueueState empty() {
        return new QueueState(1, 0, Map.of(), null, false, null);
    }

    QueueState withContent(long nextSequence, Map<String, StoredRequest> requests, RunRecord run) {
        return new QueueState(this.formatVersion, nextSequence, requests, run, this.paused, this.pauseReason);
    }

    QueueState withPause(boolean paused, String reason) {
        return new QueueState(this.formatVersion, this.nextSequence, this.requests, this.run, paused, reason);
    }
}

record StoredRequest(long sequence, String requestId, String queueId, Set<UUID> members, long version,
                      QueueRequestStatus.State state, UUID placementId, String serverId,
                      Map<UUID, StoredPlayerResponse> responses, String failure,
                      Long createdAtEpochMillis, Long updatedAtEpochMillis) {

    static StoredRequest queued(QueueRequest request, long sequence) {
        return queued(request, sequence, null);
    }

    static StoredRequest queued(QueueRequest request, long sequence, Long nowEpochMillis) {
        return new StoredRequest(sequence, request.requestId(), request.queueId().value(), request.members(),
                0, QueueRequestStatus.State.QUEUED, null, null, Map.of(), null,
                nowEpochMillis, nowEpochMillis);
    }

    boolean samePayload(QueueRequest request) {
        return this.requestId.equals(request.requestId()) && this.queueId.equals(request.queueId().value())
                && this.members.equals(request.members());
    }

    boolean terminal() {
        return this.state == QueueRequestStatus.State.COMPLETED
                || this.state == QueueRequestStatus.State.FAILED
                || this.state == QueueRequestStatus.State.CANCELLED;
    }

    StoredRequest withState(QueueRequestStatus.State newState, UUID newPlacementId, String newServerId,
                            Map<UUID, ServerSwitchRequest.PlayerResponse> newResponses, String newFailure) {
        Map<UUID, StoredPlayerResponse> storedResponses = new LinkedHashMap<>();
        newResponses.forEach((id, response) -> storedResponses.put(id, StoredPlayerResponse.from(response)));
        return new StoredRequest(this.sequence, this.requestId, this.queueId, this.members, this.version + 1,
                newState, newPlacementId, newServerId, storedResponses, newFailure,
                this.createdAtEpochMillis, this.updatedAtEpochMillis);
    }

    StoredRequest withUpdatedAt(long updatedAtEpochMillis) {
        return new StoredRequest(this.sequence, this.requestId, this.queueId, this.members, this.version,
                this.state, this.placementId, this.serverId, this.responses, this.failure,
                this.createdAtEpochMillis, updatedAtEpochMillis);
    }

    StoredRequest requeued(long newSequence, long updatedAtEpochMillis) {
        return new StoredRequest(newSequence, this.requestId, this.queueId, this.members, this.version + 1,
                QueueRequestStatus.State.QUEUED, null, null, Map.of(), null,
                this.createdAtEpochMillis, updatedAtEpochMillis);
    }

    QueueRequestStatus toStatus() {
        Map<UUID, ServerSwitchRequest.PlayerResponse> publicResponses = new LinkedHashMap<>();
        this.responses.forEach((id, response) -> publicResponses.put(id, response.toResponse()));
        return new QueueRequestStatus(new QueueRequest(this.requestId, new QueueId(this.queueId), this.members),
                this.version, this.state, this.placementId, this.serverId, publicResponses, this.failure);
    }

    QueueAdministration.QueueTicket toTicket() {
        return new QueueAdministration.QueueTicket(this.toStatus(), instant(this.createdAtEpochMillis),
                instant(this.updatedAtEpochMillis));
    }

    private static Instant instant(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis);
    }
}

record StoredPlayerResponse(boolean successful, ServerSwitchRequest.ServerSwitchRequestStatus status,
                            String serializedReason) {
    static StoredPlayerResponse from(ServerSwitchRequest.PlayerResponse response) {
        return new StoredPlayerResponse(response.isSuccessful(), response.getStatus(), response.getSerializedReason());
    }

    ServerSwitchRequest.PlayerResponse toResponse() {
        return new ServerSwitchRequest.PlayerResponse(this.successful, this.status, this.serializedReason);
    }
}

enum RunState {
    ALLOCATING,
    READY,
    PREPARING,
    PREPARED,
    TRANSFERRING,
    RELEASING,
    ABORTING
}

record StoredReservation(String requestId, String token, String serverId, Set<UUID> members,
                         long expiresAtEpochMillis) {
    static StoredReservation from(ServerPlacement.Reservation reservation) {
        return new StoredReservation(reservation.requestId(), reservation.token(), reservation.serverId(),
                reservation.members(), reservation.expiresAt().toEpochMilli());
    }

    ServerPlacement.Reservation toReservation() {
        return new ServerPlacement.Reservation(this.requestId, this.token, this.serverId, this.members,
                Instant.ofEpochMilli(this.expiresAtEpochMillis));
    }
}

record RunRecord(UUID placementId, long revision, RunState state, String serverId, String activeRequestId,
                  StoredReservation reservation, boolean handedOff, long preparationDeadlineEpochMillis,
                  long transferDeadlineEpochMillis, boolean terminateOnAbort, String abortFailure) {

    static RunRecord allocating() {
        return new RunRecord(UUID.randomUUID(), 0, RunState.ALLOCATING, null, null,
                null, false, 0, 0, false, null);
    }

    RunRecord preparing(String nextServerId, String nextRequestId, StoredReservation nextReservation,
                        boolean nextHandedOff, long preparationDeadline) {
        return new RunRecord(this.placementId, this.revision + 1, RunState.PREPARING, nextServerId, nextRequestId,
                nextReservation, nextHandedOff, preparationDeadline, 0, false, null);
    }

    RunRecord next(RunState nextState, String nextServerId, String nextRequestId,
                    StoredReservation nextReservation, boolean nextHandedOff, long nextDeadline,
                    boolean nextTerminateOnAbort, String nextAbortFailure) {
        long preparationDeadline = nextState == RunState.PREPARING || nextState == RunState.PREPARED
                ? this.preparationDeadlineEpochMillis : 0;
        return new RunRecord(this.placementId, this.revision + 1, nextState, nextServerId, nextRequestId,
                nextReservation, nextHandedOff, preparationDeadline, nextDeadline,
                nextTerminateOnAbort, nextAbortFailure);
    }
}

record QueueClaim(QueueId queueId, String token, RunRecord run, List<StoredRequest> requests) {
}

record QueueSnapshot(boolean paused, String pauseReason, List<StoredRequest> requests, RunRecord run) {
}
