package fr.codinbox.echo.core.server.placement;

import fr.codinbox.echo.core.integration.RedisIntegrationTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class RedisPlacementStoreIntegrationTest extends RedisIntegrationTestBase {
    @Test
    void recomputesWhenAnExternalPropertyChangesBeforeCommit() {
        redissonClient.getBucket("capacity").set(2);
        final AtomicInteger attempts = new AtomicInteger();
        final int selected = new RedisPlacementStore(redissonClient).execute(Duration.ofSeconds(1), tx -> {
            final int capacity = (int) tx.value("capacity");
            if (attempts.getAndIncrement() == 0) redissonClient.getBucket("capacity").set(1);
            tx.set("selection", capacity);
            return capacity;
        });
        assertThat(selected).isEqualTo(1);
        assertThat(redissonClient.getBucket("selection").get()).isEqualTo(1);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void recomputesWhenRegistryChangesWithoutChangingPlacementRevision() {
        redissonClient.<String, Long>getMap("servers:map").put("old", 1L);
        final AtomicInteger attempts = new AtomicInteger();
        final var result = new RedisPlacementStore(redissonClient).execute(Duration.ofSeconds(1), tx -> {
            final var servers = tx.serverIds();
            if (attempts.getAndIncrement() == 0) redissonClient.getMap("servers:map").remove("old");
            return servers;
        });
        assertThat(result).isEmpty();
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void expiredObservationCannotAuthorizeACommitEvenWhenItsBytesAreUnchanged() {
        redissonClient.getBucket("staff").set(true, Duration.ofMillis(100));
        assertThatThrownBy(() -> new RedisPlacementStore(redissonClient).execute(Duration.ofSeconds(1), tx -> {
            tx.value("staff");
            tx.set("authorization", true);
            pause(150);
            return true;
        })).isInstanceOf(PlacementUnavailableException.class);
        assertThat(redissonClient.getBucket("authorization").isExists()).isFalse();
    }

    @Test
    void expiredOperationCannotWriteAfterTheCallerBudget() {
        assertThatThrownBy(() -> new RedisPlacementStore(redissonClient).execute(Duration.ofMillis(50), tx -> {
            tx.set("authorization", true);
            pause(100);
            return true;
        })).isInstanceOf(PlacementUnavailableException.class);
        assertThat(redissonClient.getBucket("authorization").isExists()).isFalse();
    }

    @Test
    void malformedWriteTargetFailsBeforeAnyReservationOrBucketMutation() {
        final RedisPlacementStore store = new RedisPlacementStore(redissonClient);
        assertThatThrownBy(() -> store.execute(Duration.ofSeconds(1), tx -> {
            tx.set("first", true);
            tx.set("wrong-type", true);
            tx.reservations().put("request", "value");
            redissonClient.getList("wrong-type").add("unrelated");
            return true;
        })).isInstanceOf(RuntimeException.class);
        assertThat(redissonClient.getBucket("first").isExists()).isFalse();
        final boolean empty = store.execute(Duration.ofSeconds(1), tx -> tx.reservations().isEmpty());
        assertThat(empty).isTrue();
    }

    private static void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
}
