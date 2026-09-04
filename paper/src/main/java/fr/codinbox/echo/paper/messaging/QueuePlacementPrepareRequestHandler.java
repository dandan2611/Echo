package fr.codinbox.echo.paper.messaging;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.messaging.MessageHandler;
import fr.codinbox.echo.api.server.ServerAvailability;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import fr.codinbox.echo.paper.EchoPaper;
import fr.codinbox.echo.queue.QueueId;
import fr.codinbox.echo.queue.QueuePlacementAssignment;
import fr.codinbox.echo.queue.QueuePlacementPreparer;
import fr.codinbox.echo.queue.messaging.QueuePlacementPrepareRequest;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Validates Queue assignments and invokes the game integration on Paper's main thread. */
public final class QueuePlacementPrepareRequestHandler implements MessageHandler<QueuePlacementPrepareRequest> {

    private final EchoPaper plugin;
    private final EchoClient echo;
    private final BooleanSupplier stopping;
    private final Supplier<QueuePlacementPreparer> preparer;
    private final Clock clock;
    private final ConcurrentMap<PreparationKey, Preparation> preparations = new ConcurrentHashMap<>();

    public QueuePlacementPrepareRequestHandler(@NotNull EchoPaper plugin, @NotNull EchoClient echo,
                                                @NotNull BooleanSupplier stopping,
                                                @NotNull Supplier<QueuePlacementPreparer> preparer) {
        this(plugin, echo, stopping, preparer, Clock.systemUTC());
    }

    QueuePlacementPrepareRequestHandler(EchoPaper plugin, EchoClient echo, BooleanSupplier stopping,
                                        Supplier<QueuePlacementPreparer> preparer, Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.echo = Objects.requireNonNull(echo, "echo");
        this.stopping = Objects.requireNonNull(stopping, "stopping");
        this.preparer = Objects.requireNonNull(preparer, "preparer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onReceive(@NotNull QueuePlacementPrepareRequest request) {
        this.purgeExpiredPreparations();
        QueuePlacementAssignment assignment;
        PreparationKey key;
        try {
            assignment = new QueuePlacementAssignment(request.getPlacementId(), request.getRunVersion(),
                    new QueueId(request.getQueueId()), request.getServerId(),
                    Instant.ofEpochMilli(request.getPreparationDeadlineEpochMillis()), request.getRequests());
            key = new PreparationKey(assignment.placementId(), assignment.runVersion());
        } catch (RuntimeException error) {
            this.reply(request, QueuePlacementPreparer.Decision.reject("Invalid Queue assignment"));
            return;
        }
        if (this.isExpired(assignment)) {
            this.reply(request, QueuePlacementPreparer.Decision.reject("Queue assignment deadline expired"));
            return;
        }

        Preparation created = new Preparation(assignment, new CompletableFuture<>());
        Preparation preparation = this.preparations.putIfAbsent(key, created);
        if (preparation != null) {
            if (!preparation.assignment().equals(assignment)) {
                this.reply(request, QueuePlacementPreparer.Decision.reject(
                        "Conflicting Queue assignment revision"));
                return;
            }
            preparation.decision().whenComplete((decision, error) -> this.reply(request,
                    error == null ? decision : QueuePlacementPreparer.Decision.reject(message(error))));
            return;
        }

        created.decision().whenComplete((decision, error) -> this.reply(request,
                error == null ? decision : QueuePlacementPreparer.Decision.reject(message(error))));
        long remainingMillis = assignment.preparationDeadline().toEpochMilli() - this.clock.millis();
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin,
                () -> this.rejectIfExpired(created), Math.ceilDiv(remainingMillis, 50L));
        this.validateAndPrepare(created);
    }

    private void validateAndPrepare(Preparation preparation) {
        QueuePlacementAssignment assignment = preparation.assignment();
        Optional<String> localId = this.echo.getCurrentResourceId();
        if (localId.isEmpty() || !localId.get().equals(assignment.serverId())) {
            preparation.decision().complete(QueuePlacementPreparer.Decision.reject(
                    "Assignment targets another server"));
            return;
        }

        try {
            this.echo.getServerById(localId.get()).thenCompose(server -> {
                if (server.isEmpty())
                    return CompletableFuture.completedFuture(new Readiness(null, Optional.empty()));
                return server.get().getAvailability().thenCombine(
                        this.echo.getServerLoadManager().getCurrent(), Readiness::new);
            }).whenComplete((readiness, error) -> {
                if (this.rejectIfExpired(preparation))
                    return;
                if (error != null) {
                    preparation.decision().complete(QueuePlacementPreparer.Decision.reject(
                            "Failed to verify server readiness: " + message(error)));
                    return;
                }
                this.plugin.getServer().getScheduler().runTask(this.plugin,
                        () -> this.prepareOnMainThread(preparation, readiness));
            });
        } catch (RuntimeException error) {
            preparation.decision().complete(QueuePlacementPreparer.Decision.reject(
                    "Failed to verify server readiness: " + message(error)));
        }
    }

    private void prepareOnMainThread(Preparation preparation, Readiness readiness) {
        if (this.rejectIfExpired(preparation))
            return;
        if (this.stopping.getAsBoolean()) {
            preparation.decision().complete(QueuePlacementPreparer.Decision.reject("Server is stopping"));
            return;
        }
        if (this.plugin.isDraining()) {
            preparation.decision().complete(QueuePlacementPreparer.Decision.reject("Server is draining"));
            return;
        }
        if (readiness.availability() != ServerAvailability.ACTIVE) {
            preparation.decision().complete(QueuePlacementPreparer.Decision.reject("Server is not active"));
            return;
        }
        if (readiness.load().isEmpty()) {
            preparation.decision().complete(QueuePlacementPreparer.Decision.reject("Server load is unavailable"));
            return;
        }
        ServerLoadSnapshot load = readiness.load().get();
        if (load.isStale(this.clock.instant())) {
            preparation.decision().complete(QueuePlacementPreparer.Decision.reject("Server load is stale"));
            return;
        }
        if (!load.load().acceptingQueueAssignments()) {
            preparation.decision().complete(QueuePlacementPreparer.Decision.reject(
                    "Server is not accepting Queue assignments"));
            return;
        }

        try {
            QueuePlacementPreparer activePreparer = this.preparer.get();
            if (activePreparer == null) {
                preparation.decision().complete(QueuePlacementPreparer.Decision.reject(
                        "No QueuePlacementPreparer is registered"));
                return;
            }
            Objects.requireNonNull(activePreparer.prepare(preparation.assignment()), "preparation result")
                    .whenComplete((decision, error) -> {
                        if (this.rejectIfExpired(preparation))
                            return;
                        if (error != null)
                            preparation.decision().completeExceptionally(error);
                        else
                            preparation.decision().complete(Objects.requireNonNull(decision, "decision"));
                    });
        } catch (RuntimeException error) {
            preparation.decision().completeExceptionally(error);
        }
    }

    private void purgeExpiredPreparations() {
        this.preparations.values().forEach(this::rejectIfExpired);
    }

    private boolean rejectIfExpired(Preparation preparation) {
        if (!this.isExpired(preparation.assignment()))
            return false;
        PreparationKey key = new PreparationKey(
                preparation.assignment().placementId(), preparation.assignment().runVersion());
        this.preparations.remove(key, preparation);
        preparation.decision().complete(QueuePlacementPreparer.Decision.reject(
                "Queue assignment deadline expired"));
        return true;
    }

    private boolean isExpired(QueuePlacementAssignment assignment) {
        return !this.clock.instant().isBefore(assignment.preparationDeadline());
    }

    private void reply(QueuePlacementPrepareRequest request, QueuePlacementPreparer.Decision decision) {
        if (request.getReplyTopic() == null) {
            this.plugin.getLogger().warning("Queue preparation request has no reply topic");
            return;
        }
        QueuePlacementPrepareRequest.Response response = new QueuePlacementPrepareRequest.Response(
                request.getPlacementId(), request.getRunVersion(), decision.accepted(), decision.reason());
        response.setMessageId(request.getMessageId());
        response.setReplyTopic(this.echo.getLocalTopic());
        try {
            this.echo.getMessagingProvider().publish(request.getReplyTopic(), response)
                    .whenComplete((ignored, error) -> {
                        if (error != null)
                            this.plugin.getLogger().log(Level.WARNING,
                                    "Failed to reply to Queue preparation request", error);
                    });
        } catch (RuntimeException error) {
            this.plugin.getLogger().log(Level.WARNING, "Failed to reply to Queue preparation request", error);
        }
    }

    private static String message(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private record PreparationKey(UUID placementId, long runVersion) {
    }

    private record Preparation(QueuePlacementAssignment assignment,
                               CompletableFuture<QueuePlacementPreparer.Decision> decision) {
    }

    private record Readiness(ServerAvailability availability, Optional<ServerLoadSnapshot> load) {
    }
}
