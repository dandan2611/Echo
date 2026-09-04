package fr.codinbox.echo.queue.redis;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.messaging.MessageTarget;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.ondemand.OnDemandServers;
import fr.codinbox.echo.ondemand.ServerHandle;
import fr.codinbox.echo.ondemand.ServerRequest;
import fr.codinbox.echo.queue.QueueAdministration;
import fr.codinbox.echo.queue.QueueDefinition;
import fr.codinbox.echo.queue.QueueId;
import fr.codinbox.echo.queue.QueueOptions;
import fr.codinbox.echo.queue.QueueRequest;
import fr.codinbox.echo.queue.QueueRequestStatus;
import fr.codinbox.echo.queue.QueueService;
import fr.codinbox.echo.queue.internal.QueueServiceRegistry;
import fr.codinbox.echo.queue.messaging.QueuePlacementPrepareRequest;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Redis-backed queue coordinator. Queue state is durable; Redis pub/sub only carries bounded handoff messages. */
public final class RedisQueue implements QueueService, QueueAdministration {

    private final QueueStore store;
    private final EchoClient echo;
    private final OnDemandServers onDemand;
    private final ServerPlacement placement;
    private final Map<QueueId, QueueDefinition> definitions;
    private final QueueOptions options;
    private final Clock clock;
    private final String workerId = UUID.randomUUID().toString();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("echo-queue-poller").factory());
    private final AtomicBoolean started = new AtomicBoolean();

    public RedisQueue(@NotNull RedisConnection connection, @NotNull EchoClient echo,
                      @NotNull OnDemandServers onDemand, @NotNull ServerPlacement placement,
                       @NotNull Collection<QueueDefinition> definitions, @NotNull QueueOptions options) {
        this(new RedisQueueStore(connection), echo, onDemand, placement, definitions, options, Clock.systemUTC());
    }

    RedisQueue(QueueStore store, EchoClient echo, OnDemandServers onDemand, ServerPlacement placement,
                Collection<QueueDefinition> definitions, QueueOptions options) {
        this(store, echo, onDemand, placement, definitions, options, Clock.systemUTC());
    }

    RedisQueue(QueueStore store, EchoClient echo, OnDemandServers onDemand, ServerPlacement placement,
               Collection<QueueDefinition> definitions, QueueOptions options, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.echo = Objects.requireNonNull(echo, "echo");
        this.onDemand = Objects.requireNonNull(onDemand, "onDemand");
        this.placement = Objects.requireNonNull(placement, "placement");
        this.options = Objects.requireNonNull(options, "options");
        this.clock = Objects.requireNonNull(clock, "clock");
        Map<QueueId, QueueDefinition> configured = new LinkedHashMap<>();
        for (QueueDefinition definition : Objects.requireNonNull(definitions, "definitions")) {
            if (configured.put(definition.id(), definition) != null)
                throw new IllegalArgumentException("Duplicate queue definition: " + definition.id());
        }
        if (configured.isEmpty())
            throw new IllegalArgumentException("At least one queue definition is required");
        this.definitions = Map.copyOf(configured);
    }

    @Override
    public @NotNull CompletableFuture<Void> start() {
        if (!this.started.compareAndSet(false, true))
            return CompletableFuture.completedFuture(null);
        try {
            QueueServiceRegistry.register(this);
            this.scheduler.scheduleWithFixedDelay(this::wakeAll, this.options.pollInterval().toMillis(),
                    this.options.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
            this.wakeAll();
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException error) {
            this.started.set(false);
            QueueServiceRegistry.unregister(this);
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public @NotNull CompletableFuture<QueueRequestStatus> enqueue(@NotNull QueueRequest request) {
        QueueDefinition definition = this.definition(request.queueId());
        return this.async(() -> {
            QueueRequestStatus result = this.store.enqueue(definition, request);
            this.wake(definition);
            return result;
        });
    }

    @Override
    public @NotNull CompletableFuture<Optional<QueueRequestStatus>> get(
            @NotNull QueueId queueId, @NotNull String requestId) {
        this.definition(queueId);
        if (Objects.requireNonNull(requestId, "requestId").isBlank())
            throw new IllegalArgumentException("requestId must not be blank");
        return this.async(() -> this.store.get(queueId, requestId));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> cancel(@NotNull QueueId queueId, @NotNull String requestId) {
        QueueDefinition definition = this.definition(queueId);
        if (Objects.requireNonNull(requestId, "requestId").isBlank())
            throw new IllegalArgumentException("requestId must not be blank");
        return this.async(() -> {
            boolean cancelled = this.store.cancel(queueId, requestId);
            if (cancelled)
                this.wake(definition);
            return cancelled;
        });
    }

    @Override
    public @NotNull QueueAdministration administration() {
        return this;
    }

    @Override
    public @NotNull CompletableFuture<List<QueueOverview>> listQueues() {
        return this.async(() -> this.definitions.values().stream()
                .sorted(Comparator.comparing(definition -> definition.id().value()))
                .map(this::overview)
                .toList());
    }

    @Override
    public @NotNull CompletableFuture<List<QueueTicket>> listTickets(@NotNull QueueId queueId) {
        this.definition(queueId);
        return this.async(() -> this.store.snapshot(queueId).requests().stream()
                .sorted(Comparator.comparingLong(StoredRequest::sequence))
                .map(StoredRequest::toTicket)
                .toList());
    }

    @Override
    public @NotNull CompletableFuture<Boolean> pause(@NotNull QueueId queueId, @NotNull String reason) {
        this.definition(queueId);
        if (Objects.requireNonNull(reason, "reason").isBlank())
            throw new IllegalArgumentException("reason must not be blank");
        return this.async(() -> this.store.pause(queueId, reason));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> resume(@NotNull QueueId queueId) {
        QueueDefinition definition = this.definition(queueId);
        return this.async(() -> {
            boolean resumed = this.store.resume(queueId);
            if (resumed)
                this.wake(definition);
            return resumed;
        });
    }

    @Override
    public @NotNull CompletableFuture<Void> wake(@NotNull QueueId queueId) {
        this.wake(this.definition(queueId));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> retry(@NotNull QueueId queueId, @NotNull String requestId) {
        QueueDefinition definition = this.definition(queueId);
        if (Objects.requireNonNull(requestId, "requestId").isBlank())
            throw new IllegalArgumentException("requestId must not be blank");
        return this.async(() -> {
            boolean retried = this.store.retry(queueId, requestId);
            if (retried)
                this.wake(definition);
            return retried;
        });
    }

    @Override
    public @NotNull CompletableFuture<Integer> purgeTerminal(
            @NotNull QueueId queueId, @NotNull Duration olderThan) {
        this.definition(queueId);
        if (Objects.requireNonNull(olderThan, "olderThan").isNegative())
            throw new IllegalArgumentException("olderThan must not be negative");
        return this.async(() -> this.store.purgeTerminal(queueId, this.clock.instant().minus(olderThan)));
    }

    @Override
    public void close() {
        this.started.set(false);
        QueueServiceRegistry.unregister(this);
        this.scheduler.shutdownNow();
        this.store.close();
    }

    private QueueDefinition definition(QueueId id) {
        QueueDefinition definition = this.definitions.get(Objects.requireNonNull(id, "queueId"));
        if (definition == null)
            throw new IllegalArgumentException("Unknown queue: " + id);
        return definition;
    }

    private QueueOverview overview(QueueDefinition definition) {
        QueueSnapshot snapshot = this.store.snapshot(definition.id());
        Map<QueueRequestStatus.State, Long> counts = new EnumMap<>(QueueRequestStatus.State.class);
        for (QueueRequestStatus.State state : QueueRequestStatus.State.values())
            counts.put(state, 0L);
        snapshot.requests().forEach(request -> counts.compute(request.state(), (ignored, count) -> count + 1));
        RunRecord run = snapshot.run();
        return new QueueOverview(definition, snapshot.paused(), snapshot.pauseReason(), counts,
                run == null ? null : run.placementId(), run == null ? null : run.serverId(),
                run == null ? null : run.state().name());
    }

    private void wakeAll() {
        this.definitions.values().forEach(this::wake);
    }

    private void wake(QueueDefinition definition) {
        if (this.started.get())
            Thread.startVirtualThread(() -> this.process(definition));
    }

    private void process(QueueDefinition definition) {
        Optional<QueueClaim> claimed;
        try {
            claimed = this.store.claim(definition, this.workerId, this.options.claimTtl());
        } catch (RuntimeException ignored) {
            return;
        }
        if (claimed.isEmpty())
            return;

        QueueClaim claim = claimed.get();
        AtomicBoolean owned = new AtomicBoolean(true);
        ScheduledFuture<?> renewal = null;
        try {
            if (this.started.get()) {
                long renewalInterval = Math.max(1, this.options.claimTtl().toMillis() / 3);
                QueueClaim leasedClaim = claim;
                renewal = this.scheduler.scheduleWithFixedDelay(() -> {
                    try {
                        if (!this.store.renew(leasedClaim, this.options.claimTtl()))
                            owned.set(false);
                    } catch (RuntimeException error) {
                        owned.set(false);
                    }
                }, renewalInterval, renewalInterval, TimeUnit.MILLISECONDS);
            }
            while (this.started.get() && owned.get() && !this.store.paused(definition.id())
                    && claim.run() != null) {
                RunState previousState = claim.run().state();
                Optional<QueueClaim> next = switch (previousState) {
                    case ALLOCATING -> this.allocate(definition, claim);
                    case READY -> this.reserve(definition, claim);
                    case PREPARING -> this.prepare(claim);
                    case PREPARED -> this.startTransfer(claim);
                    case TRANSFERRING -> this.transfer(definition, claim);
                    case RELEASING -> this.releaseReservation(claim);
                    case ABORTING -> this.abort(claim);
                };
                if (next.isEmpty())
                    break;
                claim = next.get();
                if (previousState == RunState.TRANSFERRING
                        && claim.run().state() == RunState.TRANSFERRING)
                    break;
            }
        } finally {
            if (renewal != null)
                renewal.cancel(false);
            this.store.release(claim);
        }
    }

    private Optional<QueueClaim> allocate(QueueDefinition definition, QueueClaim claim) {
        if (this.queued(claim).isEmpty())
            return this.store.commit(claim, null, List.of());
        try {
            ServerHandle server = this.onDemand.acquire(new ServerRequest(
                    "queue:" + definition.id().value() + ":" + claim.run().placementId(),
                    definition.serverType(), definition.serverProperties())).join();
            return this.store.commit(claim, claim.run().next(RunState.READY, server.id(), null,
                    null, false, 0, false, null), List.of());
        } catch (RuntimeException error) {
            return Optional.empty();
        }
    }

    private Optional<QueueClaim> reserve(QueueDefinition definition, QueueClaim claim) {
        Optional<StoredRequest> queued = this.queued(claim);
        if (queued.isEmpty()) {
            if (claim.run().handedOff())
                return Optional.empty();
            return this.store.commit(claim, claim.run().next(RunState.ABORTING, claim.run().serverId(),
                    null, null, false, 0, true, "No queued requests remain"), List.of());
        }

        StoredRequest request = queued.get();
        try {
            Optional<ServerPlacement.Reservation> reserved = this.placement.reserve(
                    this.placementRequest(definition, request, claim.run().serverId())).join();
            if (reserved.isEmpty())
                return this.store.commit(claim, claim.run().next(RunState.ABORTING, claim.run().serverId(),
                        null, null, claim.run().handedOff(), 0, !claim.run().handedOff(),
                        "Allocated server cannot accept the queued group"), List.of());

            ServerPlacement.Reservation reservation = reserved.get();
            StoredRequest claimed = request.withState(QueueRequestStatus.State.CLAIMED,
                    claim.run().placementId(), reservation.serverId(), Map.of(), null);
            long preparationDeadline = this.clock.instant().plus(this.options.paperAckTimeout()).toEpochMilli();
            return this.store.commit(claim, claim.run().preparing(reservation.serverId(), request.requestId(),
                    StoredReservation.from(reservation), claim.run().handedOff(), preparationDeadline),
                    List.of(claimed));
        } catch (RuntimeException error) {
            return Optional.empty();
        }
    }

    private Optional<QueueClaim> prepare(QueueClaim claim) {
        StoredRequest request = this.active(claim);
        ServerPlacement.Reservation reservation = claim.run().reservation().toReservation();
        try {
            if (this.placement.renew(reservation, this.options.reservationTtl()).join().isEmpty())
                return this.beginAbort(claim, "Placement reservation expired");

            long preparationDeadline = claim.run().preparationDeadlineEpochMillis();
            long remainingMillis = preparationDeadline - this.clock.millis();
            if (remainingMillis <= 0)
                return this.beginAbort(claim, "Paper preparation deadline expired");
            QueuePlacementPrepareRequest message = new QueuePlacementPrepareRequest(claim.run().placementId(),
                    claim.run().revision(), claim.queueId().value(), claim.run().serverId(),
                    preparationDeadline,
                    Map.of(request.requestId(), request.members()));
            message.setReplyTopic(this.echo.getLocalTopic());
            QueuePlacementPrepareRequest.Response response = this.echo.getMessagingProvider().request(
                    this.topicForServer(claim.run().serverId()), message,
                    QueuePlacementPrepareRequest.Response.class, Duration.ofMillis(remainingMillis)).join();
            if (!claim.run().placementId().equals(response.getPlacementId())
                    || response.getRunVersion() != claim.run().revision())
                return this.beginAbort(claim, "Paper returned a stale preparation response");
            if (!response.isAccepted())
                return this.beginAbort(claim, response.getReason() == null ? "Paper rejected assignment"
                        : response.getReason());
            Optional<ServerPlacement.Reservation> renewed = this.placement
                    .renew(reservation, this.options.reservationTtl()).join();
            if (renewed.isEmpty())
                return this.beginAbort(claim, "Placement reservation expired after Paper preparation");

            StoredRequest prepared = request.withState(QueueRequestStatus.State.PREPARED,
                    claim.run().placementId(), claim.run().serverId(), request.toStatus().responses(), null);
            return this.store.commit(claim, claim.run().next(RunState.PREPARED, claim.run().serverId(),
                    request.requestId(), StoredReservation.from(renewed.get()), claim.run().handedOff(),
                    0, false, null), List.of(prepared));
        } catch (RuntimeException error) {
            return this.beginAbort(claim, message(error));
        }
    }

    private Optional<QueueClaim> beginAbort(QueueClaim claim, String reason) {
        return this.store.commit(claim, claim.run().next(RunState.ABORTING, claim.run().serverId(),
                claim.run().activeRequestId(), claim.run().reservation(), claim.run().handedOff(), 0,
                !claim.run().handedOff(), reason), List.of());
    }

    private Optional<QueueClaim> startTransfer(QueueClaim claim) {
        StoredRequest request = this.active(claim);
        try {
            Optional<ServerPlacement.Reservation> renewed = this.placement.renew(
                    claim.run().reservation().toReservation(), this.options.reservationTtl()).join();
            if (renewed.isEmpty())
                return this.beginAbort(claim, "Placement reservation expired before transfer");
            StoredRequest transferring = request.withState(QueueRequestStatus.State.TRANSFERRING,
                    claim.run().placementId(), claim.run().serverId(), request.toStatus().responses(), null);
            return this.store.commit(claim, claim.run().next(RunState.TRANSFERRING, claim.run().serverId(),
                    request.requestId(), StoredReservation.from(renewed.get()), true,
                    this.clock.instant().plus(this.options.transferTimeout()).toEpochMilli(), false, null),
                    List.of(transferring));
        } catch (RuntimeException error) {
            return this.beginAbort(claim, message(error));
        }
    }

    private Optional<QueueClaim> transfer(QueueDefinition definition, QueueClaim claim) {
        StoredRequest request = this.active(claim);
        Map<UUID, ServerSwitchRequest.PlayerResponse> responses = new LinkedHashMap<>(request.toStatus().responses());
        long deadline = claim.run().transferDeadlineEpochMillis();
        if (this.clock.millis() < deadline && claim.run().reservation() != null) {
            try {
                Optional<ServerPlacement.Reservation> renewed = this.joinBeforeDeadline(this.placement.renew(
                        claim.run().reservation().toReservation(), this.options.reservationTtl()), deadline);
                if (renewed.isEmpty()) {
                    Optional<ServerPlacement.Reservation> replacement = this.joinBeforeDeadline(
                            this.placement.reserve(this.placementRequest(
                                    definition, request, claim.run().serverId())), deadline);
                    if (replacement.isEmpty())
                        return Optional.empty();
                    return this.store.commit(claim, claim.run().next(RunState.TRANSFERRING,
                            claim.run().serverId(), request.requestId(), StoredReservation.from(replacement.get()),
                            true, deadline, false, null), List.of());
                }
            } catch (RuntimeException ignored) {
                // Retry after the next poll without starting an unreserved transfer.
                return Optional.empty();
            }
        }

        Map<String, Set<UUID>> byProxy = new LinkedHashMap<>();
        for (UUID member : request.members()) {
            if (responses.containsKey(member))
                continue;
            try {
                Optional<User> user = this.joinBeforeDeadline(this.echo.getUserById(member), deadline);
                if (user.isEmpty()) {
                    responses.put(member, response(false,
                            ServerSwitchRequest.ServerSwitchRequestStatus.PLAYER_NOT_CONNECTED));
                    continue;
                }
                Optional<String> proxy = this.joinBeforeDeadline(user.get().getCurrentProxyId(), deadline);
                if (proxy.isEmpty()) {
                    responses.put(member, response(false,
                            ServerSwitchRequest.ServerSwitchRequestStatus.PLAYER_NOT_CONNECTED));
                    continue;
                }
                byProxy.computeIfAbsent(proxy.get(), ignored -> new LinkedHashSet<>()).add(member);
            } catch (RuntimeException ignored) {
                // Unknown outcomes remain unresolved and are retried until the persisted deadline.
            }
        }

        for (Map.Entry<String, Set<UUID>> proxy : byProxy.entrySet()) {
            long remainingMillis = claim.run().transferDeadlineEpochMillis() - this.clock.millis();
            if (remainingMillis <= 0)
                break;
            try {
                ServerSwitchRequest message = new ServerSwitchRequest(claim.run().serverId(),
                        proxy.getValue().toArray(UUID[]::new));
                message.setTransferDeadlineEpochMillis(deadline);
                message.setReplyTopic(this.echo.getLocalTopic());
                ServerSwitchRequest.Response reply = this.echo.getMessagingProvider().request(
                        this.topicForProxy(proxy.getKey()), message, ServerSwitchRequest.Response.class,
                        Duration.ofMillis(remainingMillis)).join();
                proxy.getValue().forEach(member -> {
                    ServerSwitchRequest.PlayerResponse player = reply.getResponses().get(member);
                    if (player != null && !retryable(player.getStatus()))
                        responses.put(member, player);
                });
            } catch (RuntimeException ignored) {
                // Polling and the durable TRANSFERRING state provide replay.
            }
        }

        boolean deadlineReached = this.clock.millis() >= claim.run().transferDeadlineEpochMillis();
        boolean hasRecordedFailure = responses.values().stream()
                .anyMatch(response -> !response.isSuccessful());
        if (deadlineReached || responses.keySet().containsAll(request.members()) && hasRecordedFailure) {
            this.reconcileTransferredMembers(request, claim.run().serverId(), responses);
        }
        if (deadlineReached) {
            request.members().stream().filter(member -> !responses.containsKey(member)).forEach(member ->
                    responses.put(member, response(false, ServerSwitchRequest.ServerSwitchRequestStatus.TIMED_OUT)));
        }

        if (responses.keySet().containsAll(request.members())) {
            boolean success = responses.values().stream().allMatch(ServerSwitchRequest.PlayerResponse::isSuccessful);
            StoredRequest completed = request.withState(success ? QueueRequestStatus.State.COMPLETED
                            : QueueRequestStatus.State.FAILED, claim.run().placementId(), claim.run().serverId(),
                    responses, success ? null : "One or more players could not be transferred");
            return this.store.commit(claim, claim.run().next(RunState.RELEASING, claim.run().serverId(),
                    request.requestId(), claim.run().reservation(), true, 0, false, null), List.of(completed));
        }

        StoredRequest pending = request.withState(QueueRequestStatus.State.TRANSFERRING,
                claim.run().placementId(), claim.run().serverId(), responses, null);
        return this.store.commit(claim, claim.run().next(RunState.TRANSFERRING, claim.run().serverId(),
                request.requestId(), claim.run().reservation(), true,
                claim.run().transferDeadlineEpochMillis(), false, null), List.of(pending));
    }

    private <T> T joinBeforeDeadline(CompletableFuture<T> future, long deadlineEpochMillis) {
        long remainingMillis = deadlineEpochMillis - this.clock.millis();
        if (remainingMillis <= 0)
            throw new CompletionException(new TimeoutException("Transfer deadline expired"));
        return future.copy().orTimeout(remainingMillis, TimeUnit.MILLISECONDS).join();
    }

    private void reconcileTransferredMembers(StoredRequest request, String serverId,
                                              Map<UUID, ServerSwitchRequest.PlayerResponse> responses) {
        Map<UUID, CompletableFuture<Boolean>> checks = new LinkedHashMap<>();
        request.members().stream().filter(member -> {
            ServerSwitchRequest.PlayerResponse response = responses.get(member);
            return response == null || !response.isSuccessful();
        }).forEach(member -> {
            try {
                checks.put(member, this.echo.getUserById(member).thenCompose(user -> user
                        .map(value -> value.getCurrentServerId().thenApply(current -> current
                                .filter(serverId::equals).isPresent()))
                        .orElseGet(() -> CompletableFuture.completedFuture(false)))
                        .exceptionally(ignored -> false));
            } catch (RuntimeException error) {
                checks.put(member, CompletableFuture.completedFuture(false));
            }
        });
        try {
            CompletableFuture.allOf(checks.values().toArray(CompletableFuture[]::new))
                    .orTimeout(this.options.transferReconciliationTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        } catch (RuntimeException ignored) {
            // A bounded best-effort check is sufficient; unresolved members time out below.
        }
        checks.forEach((member, check) -> {
            if (check.isDone() && !check.isCompletedExceptionally() && Boolean.TRUE.equals(check.getNow(false)))
                responses.put(member, response(true,
                        ServerSwitchRequest.ServerSwitchRequestStatus.ALREADY_CONNECTED));
        });
    }

    private Optional<QueueClaim> releaseReservation(QueueClaim claim) {
        StoredRequest request = this.active(claim);
        boolean transferred = request.toStatus().responses().values().stream()
                .anyMatch(ServerSwitchRequest.PlayerResponse::isSuccessful);
        try {
            // Keep transferred players covered until Paper publishes their authoritative load.
            if (!transferred && claim.run().reservation() != null)
                this.placement.release(claim.run().reservation().toReservation()).join();
        } catch (RuntimeException error) {
            return Optional.empty();
        }
        return this.store.commit(claim, claim.run().next(RunState.READY, claim.run().serverId(),
                null, null, true, 0, false, null), List.of());
    }

    private Optional<QueueClaim> abort(QueueClaim claim) {
        try {
            if (claim.run().reservation() != null)
                this.placement.release(claim.run().reservation().toReservation()).join();
            if (claim.run().terminateOnAbort())
                this.onDemand.terminate(new ServerHandle(claim.run().serverId(),
                        "queue:" + claim.queueId().value() + ":" + claim.run().placementId())).join();
        } catch (RuntimeException error) {
            return Optional.empty();
        }

        List<StoredRequest> updates = new ArrayList<>();
        if (claim.run().activeRequestId() != null) {
            StoredRequest request = this.active(claim);
            updates.add(request.withState(QueueRequestStatus.State.QUEUED,
                    null, null, Map.of(), null));
        }
        return this.store.commit(claim, null, updates);
    }

    private Optional<StoredRequest> queued(QueueClaim claim) {
        return claim.requests().stream()
                .filter(request -> request.state() == QueueRequestStatus.State.QUEUED)
                .min(Comparator.comparingLong(StoredRequest::sequence));
    }

    private ServerPlacement.Request placementRequest(
            QueueDefinition definition, StoredRequest request, String serverId) {
        Map<PropertyKey<?>, Object> filters = new LinkedHashMap<>();
        definition.serverProperties().forEach((key, value) -> {
            if (!Set.of("availability", "load", ServerPlacement.PROPERTY_CAPACITY.key()).contains(key.key()))
                filters.put(key, value);
        });
        return new ServerPlacement.Request(
                "queue:" + definition.id().value() + ":ticket:" + request.requestId(), request.members(),
                Set.of(serverId), filters, definition.placementPolicy(), this.options.reservationTtl());
    }

    private StoredRequest active(QueueClaim claim) {
        return claim.requests().stream()
                .filter(request -> request.requestId().equals(claim.run().activeRequestId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Queue run references a missing request"));
    }

    private String topicForServer(String id) {
        return only(this.echo.newMessageTargetBuilder().withServer(id).build());
    }

    private String topicForProxy(String id) {
        return only(this.echo.newMessageTargetBuilder().withProxy(id).build());
    }

    private static String only(MessageTarget target) {
        if (target.getTargets().size() != 1)
            throw new IllegalStateException("Queue handoff requires exactly one messaging topic");
        return target.getTargets().iterator().next();
    }

    private static boolean retryable(ServerSwitchRequest.ServerSwitchRequestStatus status) {
        return status == ServerSwitchRequest.ServerSwitchRequestStatus.CONNECTION_IN_PROGRESS
                || status == ServerSwitchRequest.ServerSwitchRequestStatus.TARGET_SERVER_NOT_REGISTERED
                || status == ServerSwitchRequest.ServerSwitchRequestStatus.PLAYER_NOT_CONNECTED
                || status == ServerSwitchRequest.ServerSwitchRequestStatus.TIMED_OUT
                || status == ServerSwitchRequest.ServerSwitchRequestStatus.INTERNAL_ERROR;
    }

    private static ServerSwitchRequest.PlayerResponse response(
            boolean successful, ServerSwitchRequest.ServerSwitchRequestStatus status) {
        return new ServerSwitchRequest.PlayerResponse(successful, status, null);
    }

    private static String message(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private <T> CompletableFuture<T> async(Supplier<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                result.complete(operation.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }
}
