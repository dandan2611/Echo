package fr.codinbox.echo.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class EchoTest {

    @Mock
    private EchoClient mockClient;

    @BeforeEach
    void setUp() throws Exception {
        Field clientField = Echo.class.getDeclaredField("client");
        clientField.setAccessible(true);
        clientField.set(null, null);
    }

    @Test
    void getClient_whenNotInitialized_throwsNPE() {
        assertThatThrownBy(Echo::getClient)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Echo is not initialized");
    }

    @Test
    void initClient_setsClient() {
        Echo.initClient(mockClient);

        assertThat(Echo.getClient()).isSameAs(mockClient);
    }

    @Test
    void initClient_whenAlreadyInitialized_throwsIllegalStateException() {
        Echo.initClient(mockClient);

        assertThatThrownBy(() -> Echo.initClient(mockClient))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already initialized");
    }
}
