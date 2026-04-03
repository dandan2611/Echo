package fr.codinbox.echo.core.utils;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class FutureUtilsTest {

    @Test
    void completeAsync_shouldPreserveValue() {
        CompletableFuture<String> source = CompletableFuture.completedFuture("hello");

        CompletableFuture<String> result = FutureUtils.completeAsync(source);

        assertThat(result.join()).isEqualTo("hello");
    }

    @Test
    void completeAsync_shouldReturnADifferentFutureInstance() {
        CompletableFuture<Integer> source = CompletableFuture.completedFuture(42);

        CompletableFuture<Integer> result = FutureUtils.completeAsync(source);

        assertThat(result).isNotSameAs(source);
        assertThat(result.join()).isEqualTo(42);
    }
}
