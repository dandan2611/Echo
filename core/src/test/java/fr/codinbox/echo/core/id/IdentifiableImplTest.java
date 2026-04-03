package fr.codinbox.echo.core.id;

import fr.codinbox.echo.api.EchoFuture;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class IdentifiableImplTest {

    private static class ConcreteIdentifiable extends IdentifiableImpl<String> {

        ConcreteIdentifiable(@NotNull String id) {
            super(id);
        }

        @Override
        public @NotNull EchoFuture<@NotNull Optional<Long>> getCreationTime() {
            return EchoFuture.completed(Optional.empty());
        }
    }

    @Test
    void getId_shouldReturnCorrectId() {
        ConcreteIdentifiable identifiable = new ConcreteIdentifiable("test-id");

        assertThat(identifiable.getId()).isEqualTo("test-id");
    }
}
