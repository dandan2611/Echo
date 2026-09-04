package fr.codinbox.echo.api;

import fr.codinbox.echo.api.cache.CacheProvider;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.server.ServerLoadProvider;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Configuration for initializing the Echo client.
 *
 * <p>Bundles provider factories, resource identity, and healthcheck timing parameters.
 * Use the {@link Builder} to construct instances:</p>
 *
 * <pre>{@code
 * EchoConfig config = EchoConfig.builder()
 *     .cacheProviderFactory(myCacheFactory)
 *     .messagingProviderFactory(myMessagingFactory)
 *     .resourceType(EchoResourceType.SERVER)
 *     .resourceId("lobby-1")
 *     .build();
 * }</pre>
 *
 * @see Builder
 */
public class EchoConfig {

    public static final long DEFAULT_HEARTBEAT_TTL = 30;
    public static final long DEFAULT_HEARTBEAT_INTERVAL = 10;
    public static final long DEFAULT_SCAN_INTERVAL = 15;

    private final @NotNull Supplier<? extends CacheProvider> cacheProviderFactory;
    private final @NotNull Supplier<? extends MessagingProvider> messagingProviderFactory;
    private final @NotNull EchoResourceType resourceType;
    private final @NotNull String resourceId;
    private final long heartbeatTtlSeconds;
    private final long heartbeatIntervalSeconds;
    private final long scanIntervalSeconds;
    private final boolean cleanupEnabled;
    private final @NotNull Map<PropertyKey<?>, Object> initialProperties;
    private final @Nullable ServerLoadProvider serverLoadProvider;
    private final @Nullable ServerPlacement serverPlacement;

    private EchoConfig(final @NotNull Builder builder) {
        this.cacheProviderFactory = builder.cacheProviderFactory;
        this.messagingProviderFactory = builder.messagingProviderFactory;
        this.resourceType = builder.resourceType;
        this.resourceId = builder.resourceId;
        this.heartbeatTtlSeconds = builder.heartbeatTtlSeconds;
        this.heartbeatIntervalSeconds = builder.heartbeatIntervalSeconds;
        this.scanIntervalSeconds = builder.scanIntervalSeconds;
        this.cleanupEnabled = builder.cleanupEnabled != null
                ? builder.cleanupEnabled
                : builder.resourceType == EchoResourceType.PROXY;
        this.initialProperties = builder.initialProperties;
        this.serverLoadProvider = builder.serverLoadProvider;
        this.serverPlacement = builder.serverPlacement;
    }

    public @NotNull Supplier<? extends CacheProvider> getCacheProviderFactory() {
        return cacheProviderFactory;
    }

    public @NotNull Supplier<? extends MessagingProvider> getMessagingProviderFactory() {
        return messagingProviderFactory;
    }

    public @NotNull EchoResourceType getResourceType() {
        return resourceType;
    }

    public @NotNull String getResourceId() {
        return resourceId;
    }

    public long getHeartbeatTtlSeconds() {
        return heartbeatTtlSeconds;
    }

    public long getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public long getScanIntervalSeconds() {
        return scanIntervalSeconds;
    }

    public boolean isCleanupEnabled() {
        return cleanupEnabled;
    }

    /**
     * Gets the properties written before the local resource becomes discoverable.
     *
     * @return immutable initial properties
     */
    public @NotNull Map<PropertyKey<?>, Object> getInitialProperties() {
        return this.initialProperties;
    }

    public @Nullable ServerLoadProvider getServerLoadProvider() {
        return this.serverLoadProvider;
    }

    public @Nullable ServerPlacement getServerPlacement() {
        return this.serverPlacement;
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder instance
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link EchoConfig}.
     */
    public static class Builder {

        private Supplier<? extends CacheProvider> cacheProviderFactory;
        private Supplier<? extends MessagingProvider> messagingProviderFactory;
        private EchoResourceType resourceType;
        private String resourceId;
        private long heartbeatTtlSeconds = DEFAULT_HEARTBEAT_TTL;
        private long heartbeatIntervalSeconds = DEFAULT_HEARTBEAT_INTERVAL;
        private long scanIntervalSeconds = DEFAULT_SCAN_INTERVAL;
        private Boolean cleanupEnabled;
        private Map<PropertyKey<?>, Object> initialProperties = Map.of();
        private ServerLoadProvider serverLoadProvider;
        private ServerPlacement serverPlacement;

        private Builder() {
        }

        public @NotNull Builder cacheProviderFactory(
                final @NotNull Supplier<? extends CacheProvider> factory) {
            this.cacheProviderFactory = factory;
            return this;
        }

        public @NotNull Builder messagingProviderFactory(
                final @NotNull Supplier<? extends MessagingProvider> factory) {
            this.messagingProviderFactory = factory;
            return this;
        }

        public @NotNull Builder resourceType(final @NotNull EchoResourceType resourceType) {
            this.resourceType = resourceType;
            return this;
        }

        public @NotNull Builder resourceId(final @NotNull String resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public @NotNull Builder heartbeatTtlSeconds(long heartbeatTtlSeconds) {
            this.heartbeatTtlSeconds = heartbeatTtlSeconds;
            return this;
        }

        public @NotNull Builder heartbeatIntervalSeconds(long heartbeatIntervalSeconds) {
            this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
            return this;
        }

        public @NotNull Builder scanIntervalSeconds(long scanIntervalSeconds) {
            this.scanIntervalSeconds = scanIntervalSeconds;
            return this;
        }

        public @NotNull Builder cleanupEnabled(boolean cleanupEnabled) {
            this.cleanupEnabled = cleanupEnabled;
            return this;
        }

        /**
         * Sets properties that must exist before the local resource is published.
         *
         * @param properties initial resource properties
         * @return this builder
         */
        public @NotNull Builder initialProperties(
                final @NotNull Map<? extends PropertyKey<?>, ?> properties) {
            final Map<PropertyKey<?>, Object> copy = new LinkedHashMap<>();
            properties.forEach(copy::put);
            this.initialProperties = Map.copyOf(copy);
            return this;
        }

        /** Sets the platform's default load provider for a server resource. */
        public @NotNull Builder serverLoadProvider(final @NotNull ServerLoadProvider provider) {
            this.serverLoadProvider = provider;
            return this;
        }

        /** Sets the server placement implementation exposed by the Echo client. */
        public @NotNull Builder serverPlacement(final @NotNull ServerPlacement placement) {
            this.serverPlacement = placement;
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return the built config
         * @throws NullPointerException if required fields are not set
         */
        public @NotNull EchoConfig build() {
            if (cacheProviderFactory == null)
                throw new NullPointerException("cacheProviderFactory is required");
            if (messagingProviderFactory == null)
                throw new NullPointerException("messagingProviderFactory is required");
            if (resourceType == null)
                throw new NullPointerException("resourceType is required");
            if (resourceId == null)
                throw new NullPointerException("resourceId is required");
            if (resourceType == EchoResourceType.PROXY && serverLoadProvider != null)
                throw new IllegalArgumentException("A proxy cannot have a server load provider");
            final Set<String> reservedKeys = Set.of("creation_time", "availability", "load");
            this.initialProperties.keySet().stream()
                    .map(PropertyKey::key)
                    .filter(reservedKeys::contains)
                    .findFirst()
                    .ifPresent(key -> {
                        throw new IllegalArgumentException("Initial property is reserved by Echo: " + key);
                    });
            return new EchoConfig(this);
        }

    }

}
