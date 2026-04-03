package fr.codinbox.echo.core.integration;

import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.core.property.AbstractPropertyHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class PropertyHolderIntegrationTest extends RedisIntegrationTestBase {

    private TestPropertyHolder holder;

    static class TestPropertyHolder extends AbstractPropertyHolder<String> {
        TestPropertyHolder(String id) {
            super(id, "test:" + id);
        }
    }

    @BeforeEach
    void setUp() {
        createClient(EchoResourceType.SERVER, "test-server");
        holder = new TestPropertyHolder("holder1");
    }

    @Test
    void setAndGetProperty_roundTrip() {
        holder.setProperty("score", 42).join();

        Optional<Integer> result = holder.<Integer>getProperty("score").join();

        assertThat(result).isPresent().contains(42);
    }

    @Test
    void setProperty_withNull_deletesProperty() {
        holder.setProperty("temp", "value").join();
        holder.setProperty("temp", null).join();

        Optional<String> result = holder.<String>getProperty("temp").join();

        assertThat(result).isEmpty();
    }

    @Test
    void deleteProperty_existing_returnsTrue() {
        holder.setProperty("todelete", "val").join();

        boolean deleted = holder.deleteProperty("todelete").join();

        assertThat(deleted).isTrue();
    }

    @Test
    void hasProperty_afterSet_returnsTrue() {
        holder.setProperty("present", "yes").join();

        boolean has = holder.hasProperty("present").join();

        assertThat(has).isTrue();
    }

    @Test
    void hasProperty_afterDelete_returnsFalse() {
        holder.setProperty("gone", "yes").join();
        holder.deleteProperty("gone").join();

        boolean has = holder.hasProperty("gone").join();

        assertThat(has).isFalse();
    }

    @Test
    void getPropertiesKeys_returnsAllSetKeys() {
        holder.setProperty("alpha", 1).join();
        holder.setProperty("beta", 2).join();
        holder.setProperty("gamma", 3).join();

        Set<String> keys = holder.getPropertiesKeys().join();

        assertThat(keys).containsExactlyInAnyOrder("alpha", "beta", "gamma");
    }

    @Test
    void cleanup_removesAllProperties() {
        holder.setProperty("x", 1).join();
        holder.setProperty("y", 2).join();

        holder.cleanup().join();

        assertThat(holder.hasProperty("x").join()).isFalse();
        assertThat(holder.hasProperty("y").join()).isFalse();
        assertThat(holder.getPropertiesKeys().join()).isEmpty();
    }
}
