package fr.codinbox.echo.api.messaging;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Set;

/**
 * Defines the destination(s) for an {@link EchoMessage}.
 *
 * <p>A message target is a set of messaging topics (one per destination node). Use the static
 * factory methods for common cases, or the {@link Builder} for complex targets:</p>
 *
 * <pre>{@code
 * // Single server
 * new AlertMessage("Hello!").sendTo(MessageTarget.server("lobby-1"));
 *
 * // Multiple servers
 * MessageTarget target = MessageTarget.servers("lobby-1", "lobby-2");
 * new AlertMessage("Hello!").sendTo(target);
 *
 * // Single proxy
 * new AlertMessage("Hello!").sendTo(MessageTarget.proxy("proxy-eu"));
 *
 * // Broadcast to every node (no network call needed)
 * new AlertMessage("Broadcast!").sendTo(MessageTarget.everyone());
 *
 * // Complex target using the builder
 * MessageTarget target = client.newMessageTargetBuilder()
 *     .withServer("lobby-1")
 *     .withProxy("proxy-eu")
 *     .build();
 * }</pre>
 *
 * @see EchoMessage#sendTo(MessageTarget)
 */
public final class MessageTarget {

    /**
     * The broadcast topic that all nodes subscribe to automatically.
     * Messages published to this topic are received by every node in the network.
     */
    public static final @NotNull String BROADCAST_TOPIC = "echo:broadcast";

    private final @NotNull Set<String> targets;

    /**
     * Creates a message target with the given set of topic strings.
     *
     * <p>Prefer the static factory methods ({@link #server(String)}, {@link #everyone()}, etc.)
     * over this constructor.</p>
     *
     * @param targets the set of messaging topics to target
     */
    public MessageTarget(@NotNull Set<String> targets) {
        this.targets = targets;
    }

    /**
     * Gets an unmodifiable copy of the target topics.
     *
     * @return the set of messaging topics
     */
    public @NotNull Set<String> getTargets() {
        return Set.copyOf(this.targets);
    }

    /**
     * Creates a target for a single server.
     *
     * <pre>{@code
     * MessageTarget target = MessageTarget.server("lobby-1");
     * message.sendTo(target);
     * }</pre>
     *
     * @param serverId the server identifier
     * @return a message target for the specified server
     */
    public static @NotNull MessageTarget server(final @NotNull String serverId) {
        return Echo.getClient().newMessageTargetBuilder().withServer(serverId).build();
    }

    /**
     * Creates a target for multiple servers.
     *
     * <pre>{@code
     * MessageTarget target = MessageTarget.servers("lobby-1", "lobby-2", "lobby-3");
     * message.sendTo(target);
     * }</pre>
     *
     * @param serverIds the server identifiers
     * @return a message target for the specified servers
     */
    public static @NotNull MessageTarget servers(final @NotNull String... serverIds) {
        return Echo.getClient().newMessageTargetBuilder().withServers(serverIds).build();
    }

    /**
     * Creates a target for a single proxy.
     *
     * <pre>{@code
     * MessageTarget target = MessageTarget.proxy("proxy-eu");
     * message.sendTo(target);
     * }</pre>
     *
     * @param proxyId the proxy identifier
     * @return a message target for the specified proxy
     */
    public static @NotNull MessageTarget proxy(final @NotNull String proxyId) {
        return Echo.getClient().newMessageTargetBuilder().withProxy(proxyId).build();
    }

    /**
     * Creates a target for multiple proxies.
     *
     * <pre>{@code
     * MessageTarget target = MessageTarget.proxies("proxy-eu", "proxy-us");
     * message.sendTo(target);
     * }</pre>
     *
     * @param proxyIds the proxy identifiers
     * @return a message target for the specified proxies
     */
    public static @NotNull MessageTarget proxies(final @NotNull String... proxyIds) {
        return Echo.getClient().newMessageTargetBuilder().withProxies(proxyIds).build();
    }

    /**
     * Creates a target that reaches every node on the network.
     *
     * <p>This uses the {@link #BROADCAST_TOPIC} and does not require any network call
     * (no need to query the list of servers/proxies). All nodes automatically subscribe
     * to the broadcast topic.</p>
     *
     * <pre>{@code
     * new AlertMessage("Maintenance in 5 minutes!").sendTo(MessageTarget.everyone());
     * }</pre>
     *
     * @return a message target for all nodes
     */
    public static @NotNull MessageTarget everyone() {
        return new MessageTarget(Set.of(BROADCAST_TOPIC));
    }

    /**
     * Builder for constructing complex {@link MessageTarget} instances.
     *
     * <p>Obtain a builder via {@link fr.codinbox.echo.api.EchoClient#newMessageTargetBuilder()}:</p>
     *
     * <pre>{@code
     * MessageTarget target = client.newMessageTargetBuilder()
     *     .withServer("lobby-1")
     *     .withServer("lobby-2")
     *     .withProxy("proxy-eu")
     *     .build();
     *
     * // Include all servers asynchronously
     * MessageTarget allServers = client.newMessageTargetBuilder()
     *     .withAllServers().await()
     *     .build();
     * }</pre>
     */
    public interface Builder {

        /**
         * Adds a single server to the target.
         *
         * @param serverId the server identifier
         * @return this builder for chaining
         */
        @NotNull Builder withServer(final @NotNull String serverId);

        /**
         * Adds multiple servers to the target.
         *
         * @param serverIds the server identifiers
         * @return this builder for chaining
         */
        @NotNull Builder withServers(final @NotNull String... serverIds);

        /**
         * Adds multiple servers to the target from a collection.
         *
         * @param serverIds the server identifiers
         * @return this builder for chaining
         */
        @NotNull Builder withServers(final @NotNull Collection<String> serverIds);

        /**
         * Adds all currently registered servers to the target.
         *
         * <p>This requires a network call to query the server list.</p>
         *
         * <pre>{@code
         * MessageTarget target = client.newMessageTargetBuilder()
         *     .withAllServers().await()
         *     .build();
         * }</pre>
         *
         * @return a future that completes with this builder (for chaining)
         */
        @NotNull EchoFuture<@NotNull Builder> withAllServers();

        /**
         * Adds a single proxy to the target.
         *
         * @param proxyId the proxy identifier
         * @return this builder for chaining
         */
        @NotNull Builder withProxy(final @NotNull String proxyId);

        /**
         * Adds multiple proxies to the target.
         *
         * @param proxyIds the proxy identifiers
         * @return this builder for chaining
         */
        @NotNull Builder withProxies(final @NotNull String... proxyIds);

        /**
         * Adds multiple proxies to the target from a collection.
         *
         * @param proxyIds the proxy identifiers
         * @return this builder for chaining
         */
        @NotNull Builder withProxies(final @NotNull Collection<String> proxyIds);

        /**
         * Adds all currently registered proxies to the target.
         *
         * <p>This requires a network call to query the proxy list.</p>
         *
         * <pre>{@code
         * MessageTarget target = client.newMessageTargetBuilder()
         *     .withAllProxies().await()
         *     .build();
         * }</pre>
         *
         * @return a future that completes with this builder (for chaining)
         */
        @NotNull EchoFuture<@NotNull Builder> withAllProxies();

        /**
         * Adds all servers and all proxies to the target.
         *
         * <p>This requires network calls to query both server and proxy lists.
         * For instant broadcast without network calls, use {@link #withBroadcast()} instead.</p>
         *
         * @return a future that completes with this builder (for chaining)
         */
        default @NotNull EchoFuture<@NotNull Builder> withEveryone() {
            return EchoFuture.of(this.withAllServers().thenCompose(Builder::withAllProxies));
        }

        /**
         * Adds the broadcast topic to the target.
         *
         * <p>This is instant and does not require any network call. All nodes automatically
         * subscribe to the broadcast topic. Prefer this over {@link #withEveryone()} when
         * you want to reach all nodes without querying the server/proxy lists.</p>
         *
         * @return this builder for chaining
         */
        @NotNull Builder withBroadcast();

        /**
         * Builds the {@link MessageTarget} from the accumulated targets.
         *
         * @return the constructed message target
         */
        @NotNull MessageTarget build();

    }

}
