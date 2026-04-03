package fr.codinbox.echo.api.exception;

import fr.codinbox.echo.api.exception.resource.UnknownProxyException;
import fr.codinbox.echo.api.exception.resource.UnknownServerException;
import fr.codinbox.echo.api.exception.resource.UnknownUserException;
import fr.codinbox.echo.api.exception.user.UserHasNoProxyException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ExceptionTest {

    @Test
    void unknownResourceException_messageFormat() {
        UnknownResourceException ex = new UnknownResourceException("type", "name");

        assertThat(ex.getMessage()).contains("type").contains("name");
    }

    @Test
    void unknownServerException_messageContainsServerAndName() {
        UnknownServerException ex = new UnknownServerException("myServer");

        assertThat(ex).isInstanceOf(UnknownResourceException.class);
        assertThat(ex.getMessage()).contains("server").contains("myServer");
    }

    @Test
    void unknownProxyException_messageContainsProxyAndName() {
        UnknownProxyException ex = new UnknownProxyException("myProxy");

        assertThat(ex).isInstanceOf(UnknownResourceException.class);
        assertThat(ex.getMessage()).contains("proxy").contains("myProxy");
    }

    @Test
    void unknownProxyException_defaultConstructorExists() {
        UnknownProxyException ex = new UnknownProxyException();

        assertThat(ex).isInstanceOf(UnknownResourceException.class);
        assertThat(ex.getMessage()).contains("proxy");
    }

    @Test
    void unknownUserException_messageContainsUserAndName() {
        UnknownUserException ex = new UnknownUserException("myUser");

        assertThat(ex).isInstanceOf(UnknownResourceException.class);
        assertThat(ex.getMessage()).contains("user").contains("myUser");
    }

    @Test
    void userHasNoProxyException_messageContainsUuid() {
        UUID uuid = UUID.randomUUID();
        UserHasNoProxyException ex = new UserHasNoProxyException(uuid);

        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).contains(uuid.toString());
    }
}
