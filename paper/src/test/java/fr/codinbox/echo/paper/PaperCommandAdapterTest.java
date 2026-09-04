package fr.codinbox.echo.paper;

import fr.codinbox.echo.commands.CommandAudience;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class PaperCommandAdapterTest {

    @Test
    void audienceUsesTheUnderlyingPaperSender() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        CommandSender sender = mock(CommandSender.class);
        Component message = Component.text("Echo");
        when(source.getSender()).thenReturn(sender);
        when(sender.getName()).thenReturn("CONSOLE");

        CommandAudience<CommandSourceStack> audience = EchoPaper.commandAudience();
        audience.send(source, message);

        verify(sender).sendMessage(message);
        assertThat(audience.identity(source)).isEqualTo("CONSOLE");
    }
}
