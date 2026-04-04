package fr.codinbox.echo.api;

import fr.codinbox.echo.api.cache.CacheProviderFactory;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.MessagingProviderFactory;
import org.jetbrains.annotations.NotNull;

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

    private final @NotNull CacheProviderFactory cacheProviderFactory;
    private final @NotNull MessagingProviderFactory messagingProviderFactory;
    private final @NotNull EchoResourceType resourceType;
    private final @NotNull String resourceId;
    private final long heartbeatTtlSeconds;
    private final long heartbeatIntervalSeconds;
    private final long scanIntervalSeconds;
    private final boolean cleanupEnabled;

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
    }

    public @NotNull CacheProviderFactory getCacheProviderFactory() {
        return cacheProviderFactory;
    }

    public @NotNull MessagingProviderFactory getMessagingProviderFactory() {
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

        private CacheProviderFactory cacheProviderFactory;
        private MessagingProviderFactory messagingProviderFactory;
        private EchoResourceType resourceType;
        private String resourceId;
        private long heartbeatTtlSeconds = DEFAULT_HEARTBEAT_TTL;
        private long heartbeatIntervalSeconds = DEFAULT_HEARTBEAT_INTERVAL;
        private long scanIntervalSeconds = DEFAULT_SCAN_INTERVAL;
        private Boolean cleanupEnabled;

        private Builder() {
        }

        public @NotNull Builder cacheProviderFactory(final @NotNull CacheProviderFactory factory) {
            this.cacheProviderFactory = factory;
            return this;
        }

        public @NotNull Builder messagingProviderFactory(final @NotNull MessagingProviderFactory factory) {
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
            return new EchoConfig(this);
        }

    }

}
