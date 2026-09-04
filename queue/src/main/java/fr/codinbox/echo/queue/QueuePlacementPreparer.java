package fr.codinbox.echo.queue;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Game-plugin seam used to accept or reject an assignment before transfer. */
@FunctionalInterface
public interface QueuePlacementPreparer {

    @NotNull CompletionStage<Decision> prepare(@NotNull QueuePlacementAssignment assignment);

    record Decision(boolean accepted, @Nullable String reason) {

        public Decision {
            if (accepted && reason != null)
                throw new IllegalArgumentException("An accepted decision must not have a reason");
            if (!accepted && (reason == null || reason.isBlank()))
                throw new IllegalArgumentException("A rejected decision requires a reason");
        }

        public static @NotNull Decision accept() {
            return new Decision(true, null);
        }

        public static @NotNull Decision reject(@NotNull String reason) {
            return new Decision(false, Objects.requireNonNull(reason, "reason"));
        }
    }
}
