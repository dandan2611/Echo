package fr.codinbox.echo.api.user;

import org.jetbrains.annotations.CheckReturnValue;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface UserHolder {

    @CheckReturnValue
    @NotNull CompletableFuture<Map<String, Instant>> getConnectedUsers();

    @CheckReturnValue
    @NotNull CompletableFuture<Boolean> hasUser(final @NotNull UUID id);

    @NotNull CompletableFuture<Void> registerUser(final @NotNull User user);

    @NotNull CompletableFuture<Void> unregisterUser(final @NotNull User user);

}
