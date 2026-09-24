package fr.codinbox.echo.core.integration;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.EchoConfig;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.core.EchoClientImpl;
import fr.codinbox.echo.core.RedisProviderFactory;
import fr.codinbox.echo.core.testutils.EchoTestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public abstract class RedisIntegrationTestBase {

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine")
                    .withExposedPorts(6379);

    protected static RedissonClient redissonClient;
    protected static RedisConnection mockConnection;
    private static String namespace;

    @BeforeAll
    static void setupRedisson() {
        final String local = System.getProperty("echo.test.redis");
        if (local != null && !local.matches("redis://127\\.0\\.0\\.1:1[0-9]{4}"))
            throw new IllegalArgumentException("External test Redis must be disposable loopback on a lab port");
        if (local == null)
            REDIS.start();
        Config config = new Config();
        namespace = "echo-test:" + java.util.UUID.randomUUID() + ":";
        config.useSingleServer()
                .setAddress(local == null ? "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) : local)
                .setNameMapper(new org.redisson.api.NameMapper() {
                    public String map(String name) { return namespace + name; }
                    public String unmap(String name) { return name.startsWith(namespace) ? name.substring(namespace.length()) : name; }
                });
        redissonClient = Redisson.create(config);
        mockConnection = mock(RedisConnection.class);
        when(mockConnection.getClient()).thenReturn(redissonClient);
    }

    @BeforeEach
    void resetEcho() {
        EchoTestUtils.resetEchoClient();
        redissonClient.getKeys().deleteByPattern("*"); // NameMapper confines cleanup to this test namespace.
    }

    @AfterAll
    static void teardown() {
        if (redissonClient != null && !redissonClient.isShutdown()) {
            redissonClient.getKeys().deleteByPattern("*");
            redissonClient.shutdown();
        }
        if (REDIS.isRunning())
            REDIS.stop();
    }

    protected EchoClientImpl createClient(EchoResourceType type, String id) {
        EchoConfig config = EchoConfig.builder()
                .cacheProviderFactory(RedisProviderFactory.cacheFactory(mockConnection))
                .messagingProviderFactory(RedisProviderFactory.messagingFactory(mockConnection))
                .resourceType(type)
                .resourceId(id)
                .build();
        return new EchoClientImpl(config);
    }

    protected void createHeartbeat(EchoResourceType type, String id) {
        String key = "heartbeat:" + type.name().toLowerCase() + ":" + id;
        redissonClient.getBucket(key).set(Instant.now().toEpochMilli());
        redissonClient.getBucket(key).expire(Duration.ofSeconds(300));
    }
}
