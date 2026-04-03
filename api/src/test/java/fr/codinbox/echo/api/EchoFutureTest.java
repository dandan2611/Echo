package fr.codinbox.echo.api;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class EchoFutureTest {

    @Test
    void await_returnsResult() {
        EchoFuture<String> future = new EchoFuture<>();
        future.complete("hello");

        assertThat(future.await()).isEqualTo("hello");
    }

    @Test
    void await_propagatesException() {
        EchoFuture<String> future = new EchoFuture<>();
        future.completeExceptionally(new RuntimeException("boom"));

        assertThatThrownBy(future::await)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("boom");
    }

    @Test
    void of_wrapsCompletableFuture() {
        CompletableFuture<Integer> cf = CompletableFuture.completedFuture(42);

        EchoFuture<Integer> result = EchoFuture.of(cf);

        assertThat(result).isInstanceOf(EchoFuture.class);
        assertThat(result.await()).isEqualTo(42);
    }

    @Test
    void of_returnsEchoFutureUnchanged() {
        EchoFuture<Integer> original = EchoFuture.completed(42);

        EchoFuture<Integer> result = EchoFuture.of(original);

        assertThat(result).isSameAs(original);
    }

    @Test
    void completed_returnsImmediateResult() {
        EchoFuture<String> future = EchoFuture.completed("done");

        assertThat(future.isDone()).isTrue();
        assertThat(future.await()).isEqualTo("done");
    }

    @Test
    void newIncompleteFuture_returnsEchoFuture() {
        EchoFuture<String> future = new EchoFuture<>();

        CompletableFuture<String> chained = future.thenApply(s -> s + "!");

        assertThat(chained).isInstanceOf(EchoFuture.class);
    }
}
