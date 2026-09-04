package fr.codinbox.echo.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import fr.codinbox.echo.commands.CommandAudience;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class VelocityCommandAdapterTest {

    @Test
    void audienceUsesVelocitySenders() {
        Player player = mock(Player.class);
        ConsoleCommandSource console = mock(ConsoleCommandSource.class);
        Component message = Component.text("Echo");
        when(player.getUsername()).thenReturn("PlayerOne");

        CommandAudience<CommandSource> audience = EchoPlugin.commandAudience();
        audience.send(player, message);

        verify(player).sendMessage(message);
        assertThat(audience.identity(player)).isEqualTo("PlayerOne");
        assertThat(audience.identity(console)).isEqualTo("CONSOLE");
    }
}
