package fr.codinbox.echo.ondemand;

import fr.codinbox.echo.api.server.ServerAvailability;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Administrative view and controls for on-demand server allocations. */
public interface OnDemandAdministration {

    @NotNull CompletableFuture<List<Allocation>> listAllocations();

    @NotNull CompletableFuture<Optional<Allocation>> getAllocation(@NotNull String requestId);

    @NotNull CompletableFuture<Boolean> terminate(@NotNull String requestId);

    @NotNull CompletableFuture<Optional<Reconciliation>> reconcile(@NotNull String requestId);

    record Allocation(@NotNull String requestId, @NotNull String serverId) {

        public Allocation {
            if (Objects.requireNonNull(requestId, "requestId").isBlank())
                throw new IllegalArgumentException("requestId must not be blank");
            if (Objects.requireNonNull(serverId, "serverId").isBlank())
                throw new IllegalArgumentException("serverId must not be blank");
        }
    }

    record Reconciliation(
            @NotNull Allocation allocation,
            boolean live,
            @Nullable ServerAvailability availability) {

        public Reconciliation {
            Objects.requireNonNull(allocation, "allocation");
            if (live != (availability != null))
                throw new IllegalArgumentException("Only a live server can have availability");
        }
    }
}
