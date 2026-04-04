package fr.codinbox.echo.api.id;

import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Represents a resource that has a unique identity and a creation timestamp.
 *
 * <p>All Echo network resources ({@link fr.codinbox.echo.api.user.User User},
 * {@link fr.codinbox.echo.api.server.Server Server},
 * {@link fr.codinbox.echo.api.proxy.Proxy Proxy}) implement this interface.</p>
 *
 * @param <T> the type of the identifier (e.g. {@link java.util.UUID} for users,
 *            {@link String} for servers and proxies)
 */
public interface Identifiable<T> {

    /**
     * Gets the unique identifier of this resource.
     *
     * <pre>{@code
     * // For a User (identified by UUID)
     * UUID playerId = user.getId();
     *
     * // For a Server or Proxy (identified by String)
     * String serverId = server.getId();
     * }</pre>
     *
     * @return the unique identifier
     */
    @NotNull T getId();

    /**
     * Gets the timestamp (milliseconds since epoch) at which this resource was created.
     *
     * <pre>{@code
     * Optional<Long> createdAt = server.getCreationTime().await();
     * createdAt.ifPresent(ts ->
     *     System.out.println("Created at: " + Instant.ofEpochMilli(ts))
     * );
     * }</pre>
     *
     * @return a future that completes with the creation timestamp, or empty if unknown
     */
    @NotNull EchoFuture<@NotNull Optional<Long>> getCreationTime();

}
