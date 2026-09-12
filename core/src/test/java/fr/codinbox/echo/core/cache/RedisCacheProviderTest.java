package fr.codinbox.echo.core.cache;

import fr.codinbox.connector.commons.redis.RedisConnection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.misc.CompletableFutureWrapper;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
class RedisCacheProviderTest {

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void setObjectWithTtl_rejectsNonPositiveDuration(long milliseconds) {
        RedisCacheProvider provider = new RedisCacheProvider(mock(RedisConnection.class));

        assertThatThrownBy(() -> provider.setObject("heartbeat", 1L, Duration.ofMillis(milliseconds)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cache TTL must be positive");
    }

    @Test
    void setObjectWithTtl_usesOneRedisWrite() {
        RedisConnection connection = mock(RedisConnection.class);
        RedissonClient client = mock(RedissonClient.class);
        RBucket<Long> bucket = mock(RBucket.class);
        Duration ttl = Duration.ofSeconds(30);
        when(connection.getClient()).thenReturn(client);
        when(client.<Long>getBucket("heartbeat")).thenReturn(bucket);
        when(bucket.setAsync(1L, ttl)).thenReturn(new CompletableFutureWrapper<>((Void) null));

        new RedisCacheProvider(connection).setObject("heartbeat", 1L, ttl).join();

        verify(bucket).setAsync(1L, ttl);
        verifyNoMoreInteractions(bucket);
    }

    @Test
    void withLock_releasesLockFromTheAcquiringThread() throws Exception {
        RedisConnection connection = mock(RedisConnection.class);
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        AtomicLong ownerThread = new AtomicLong();
        when(connection.getClient()).thenReturn(client);
        when(client.getLock("allocation")).thenReturn(lock);
        when(lock.tryLock(1, 1, TimeUnit.SECONDS)).thenAnswer(invocation -> {
            ownerThread.set(Thread.currentThread().threadId());
            return true;
        });
        when(lock.isHeldByCurrentThread()).thenAnswer(invocation ->
                Thread.currentThread().threadId() == ownerThread.get());

        CompletableFuture<Void> action = new CompletableFuture<>();
        CountDownLatch actionStarted = new CountDownLatch(1);
        CompletableFuture<Boolean> result = new RedisCacheProvider(connection)
                .withLock("allocation", 1, 1, TimeUnit.SECONDS, () -> {
                    actionStarted.countDown();
                    return action;
                });
        assertThat(actionStarted.await(1, TimeUnit.SECONDS)).isTrue();
        action.complete(null);

        assertThat(result.join()).isTrue();
        verify(lock).unlock();
    }

    @Test
    void withLock_usesWatchdogWhenLeaseIsNonPositive() throws Exception {
        RedisConnection connection = mock(RedisConnection.class);
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(connection.getClient()).thenReturn(client);
        when(client.getLock("allocation")).thenReturn(lock);
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThat(new RedisCacheProvider(connection)
                .withLock("allocation", 1, 0, TimeUnit.SECONDS,
                        () -> CompletableFuture.completedFuture(null))
                .join()).isTrue();

        verify(lock).tryLock(1, TimeUnit.SECONDS);
        verify(lock).unlock();
    }
}
