package fr.codinbox.echo.core.integration;

import fr.codinbox.echo.api.cache.CacheMap;
import fr.codinbox.echo.api.cache.CacheProvider;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.core.EchoClientImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class CacheIntegrationTest extends RedisIntegrationTestBase {

    private CacheProvider cacheProvider;

    @BeforeEach
    void setUp() {
        EchoClientImpl client = createClient(EchoResourceType.SERVER, "test-server");
        cacheProvider = client.getCacheProvider();
    }

    @Test
    void setAndGetObject_roundTrip() {
        cacheProvider.setObject("test:key", "hello").join();

        String result = (String) cacheProvider.getObject("test:key").join();

        assertThat(result).isEqualTo("hello");
    }

    @Test
    void getObject_nonExistent_returnsNull() {
        Object result = cacheProvider.getObject("nonexistent:key").join();

        assertThat(result).isNull();
    }

    @Test
    void deleteObject_removesKey() {
        cacheProvider.setObject("test:del", "value").join();

        boolean deleted = cacheProvider.deleteObject("test:del").join();

        assertThat(deleted).isTrue();
        assertThat(cacheProvider.getObject("test:del").join()).isNull();
    }

    @Test
    void hasObject_existingKey_returnsTrue() {
        cacheProvider.setObject("test:exists", "value").join();

        boolean exists = cacheProvider.hasObject("test:exists").join();

        assertThat(exists).isTrue();
    }

    @Test
    void hasObject_nonExistentKey_returnsFalse() {
        boolean exists = cacheProvider.hasObject("test:nokey").join();

        assertThat(exists).isFalse();
    }

    @Test
    void expireObject_keyExpiresAfterTimeout() throws InterruptedException {
        cacheProvider.setObject("test:expire", "temp").join();

        Instant expireAt = Instant.now().plusSeconds(1);
        cacheProvider.expireObject("test:expire", expireAt).join();

        Thread.sleep(1500);

        Object result = cacheProvider.getObject("test:expire").join();
        assertThat(result).isNull();
    }

    @Test
    void getMap_putAndGet() {
        CacheMap<String, String> map = cacheProvider.getMap("test:map");
        map.putAsync("key1", "value1").join();

        String result = map.getAsync("key1").join();

        assertThat(result).isEqualTo("value1");
    }

    @Test
    void getKeys_matchesPattern() {
        cacheProvider.setObject("pattern:a", "1").join();
        cacheProvider.setObject("pattern:b", "2").join();
        cacheProvider.setObject("pattern:c", "3").join();
        cacheProvider.setObject("other:d", "4").join();

        Set<String> keys = cacheProvider.getKeys("pattern:*").join();

        assertThat(keys).containsExactlyInAnyOrder("pattern:a", "pattern:b", "pattern:c");
    }
}
