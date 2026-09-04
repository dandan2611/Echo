package fr.codinbox.echo.core.cache;

import fr.codinbox.connector.commons.redis.RedisConnection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class RedisCacheProviderTest {

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
