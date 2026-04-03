package fr.codinbox.echo.core.integration;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.core.EchoClientImpl;
import fr.codinbox.echo.core.testutils.EchoTestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
public abstract class RedisIntegrationTestBase {

    @Container
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine")
                    .withExposedPorts(6379);

    protected static RedissonClient redissonClient;
    protected static RedisConnection mockConnection;

    @BeforeAll
    static void setupRedisson() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redissonClient = Redisson.create(config);
        mockConnection = mock(RedisConnection.class);
        when(mockConnection.getClient()).thenReturn(redissonClient);
    }

    @BeforeEach
    void resetEcho() {
        EchoTestUtils.resetEchoClient();
        redissonClient.getKeys().flushall();
    }

    @AfterAll
    static void teardown() {
        if (redissonClient != null && !redissonClient.isShutdown()) {
            redissonClient.shutdown();
        }
    }

    protected EchoClientImpl createClient(EchoResourceType type, String id) {
        return new EchoClientImpl(mockConnection, type, id);
    }

    protected void createHeartbeat(EchoResourceType type, String id) {
        String key = "heartbeat:" + type.name().toLowerCase() + ":" + id;
        redissonClient.getBucket(key).set(Instant.now().toEpochMilli());
        redissonClient.getBucket(key).expire(Duration.ofSeconds(300));
    }
}
