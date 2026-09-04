package fr.codinbox.echo.queue.redis;

import fr.codinbox.connector.commons.redis.RedisConnection;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import fr.codinbox.echo.queue.QueueDefinition;
import fr.codinbox.echo.queue.QueueId;
import fr.codinbox.echo.queue.QueueRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("integration")
@Testcontainers
class RedisQueueStoreIntegrationTest {

    private static final QueueId QUEUE_ID = new QueueId("survival:classic");
    private static final QueueDefinition DEFINITION = new QueueDefinition(QUEUE_ID, "survival", Map.of(),
            ServerPlacement.Policy.FILL_MOST_LOADED);

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine")
            .withExposedPorts(6379);

    private static RedissonClient firstClient;
    private static RedissonClient secondClient;
    private RedisQueueStore firstStore;
    private RedisQueueStore secondStore;

    @BeforeAll
    static void connect() {
        firstClient = client();
        secondClient = client();
    }

    @BeforeEach
    void reset() {
        firstClient.getKeys().flushall();
        this.firstStore = new RedisQueueStore(connection(firstClient));
        this.secondStore = new RedisQueueStore(connection(secondClient));
    }

    @AfterAll
    static void disconnect() {
        if (firstClient != null)
            firstClient.shutdown();
        if (secondClient != null)
            secondClient.shutdown();
    }

    @Test
    void concurrentEnqueuesShareOneRunAndReceiveUniqueSequences() {
        QueueRequest first = request("ticket-1");
        QueueRequest second = request("ticket-2");
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<Void> firstEnqueue = CompletableFuture.runAsync(() -> {
            await(start);
            this.firstStore.enqueue(DEFINITION, first);
        });
        CompletableFuture<Void> secondEnqueue = CompletableFuture.runAsync(() -> {
            await(start);
            this.secondStore.enqueue(DEFINITION, second);
        });
        start.countDown();
        CompletableFuture.allOf(firstEnqueue, secondEnqueue).join();

        QueueClaim claim = this.firstStore.claim(DEFINITION, "worker", Duration.ofSeconds(30)).orElseThrow();

        assertThat(claim.run().state()).isEqualTo(RunState.ALLOCATING);
        assertThat(claim.run().revision()).isZero();
        assertThat(claim.requests()).extracting(StoredRequest::requestId)
                .containsExactlyInAnyOrder("ticket-1", "ticket-2");
        assertThat(claim.requests()).extracting(StoredRequest::sequence)
                .containsExactlyInAnyOrder(0L, 1L);
        this.firstStore.release(claim);
    }

    @Test
    void onlyOneWorkerOwnsTheClaimAndAReleasedOwnerCannotCommitAfterTakeover() {
        this.firstStore.enqueue(DEFINITION, request("ticket-1"));
        QueueClaim first = this.firstStore.claim(DEFINITION, "worker-1", Duration.ofSeconds(30)).orElseThrow();

        assertThat(this.secondStore.claim(DEFINITION, "worker-2", Duration.ofSeconds(30))).isEmpty();
        this.firstStore.release(first);
        QueueClaim second = this.secondStore.claim(DEFINITION, "worker-2", Duration.ofSeconds(30)).orElseThrow();

        RunRecord staleUpdate = first.run().next(RunState.READY, "game-1", null, null,
                false, 0, false, null);
        assertThat(this.firstStore.commit(first, staleUpdate, List.of())).isEmpty();
        assertThat(second.run()).isEqualTo(first.run());
        this.secondStore.release(second);
    }

    @Test
    void expiredClaimAllowsTakeoverAndStaleReleaseCannotDeleteItsSuccessor() {
        this.firstStore.enqueue(DEFINITION, request("ticket-1"));
        QueueClaim first = this.firstStore.claim(DEFINITION, "worker-1", Duration.ofSeconds(30)).orElseThrow();
        assertThat(this.firstStore.renew(first, Duration.ofSeconds(45))).isTrue();

        String claimKey = firstClient.getKeys().getKeysByPattern("echo:queue:*:claim").iterator().next();
        firstClient.getBucket(claimKey).delete();
        QueueClaim second = this.secondStore.claim(DEFINITION, "worker-2", Duration.ofSeconds(30)).orElseThrow();
        this.firstStore.release(first);

        assertThat(this.firstStore.renew(first, Duration.ofSeconds(30))).isFalse();
        assertThat(this.secondStore.renew(second, Duration.ofSeconds(30))).isTrue();
        assertThat(this.firstStore.claim(DEFINITION, "worker-3", Duration.ofSeconds(30))).isEmpty();
        this.secondStore.release(second);
    }

    @Test
    void idempotencyAndPayloadConflictsAreSharedAcrossStoreInstances() {
        QueueRequest request = request("ticket-1");

        assertThat(this.secondStore.enqueue(DEFINITION, request))
                .isEqualTo(this.firstStore.enqueue(DEFINITION, request));
        assertThatThrownBy(() -> this.secondStore.enqueue(DEFINITION,
                new QueueRequest(request.requestId(), QUEUE_ID, Set.of(UUID.randomUUID()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different payload");
    }

    private static QueueRequest request(String id) {
        return new QueueRequest(id, QUEUE_ID, Set.of(UUID.randomUUID()));
    }

    private static RedissonClient client() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        return Redisson.create(config);
    }

    private static RedisConnection connection(RedissonClient client) {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.getClient()).thenReturn(client);
        return connection;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}
