package fr.codinbox.echo.velocity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import fr.codinbox.echo.commands.CommandAudience;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class VelocityCommandAdapterTest {

    @AfterEach
    void releaseMockedCommandTrees() {
        // Inline mock retention otherwise keeps every generated Brigadier tree alive across tests.
        framework().clearInlineMocks();
    }

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

    @ParameterizedTest
    @ValueSource(
            strings = {
                "send server:quake lobby --proxy all",
                "echo user send server:quake lobby --proxy all"
            })
    void brigadierAcceptsUnquotedServerSelectors(String input) throws Exception {
        var echo = mock(fr.codinbox.echo.api.EchoClient.class);
        var server = mock(fr.codinbox.echo.api.server.Server.class);
        when(echo.getServerById(anyString()))
                .thenReturn(
                        fr.codinbox.echo.api.EchoFuture.completed(java.util.Optional.of(server)));
        when(server.getConnectedUsers())
                .thenReturn(fr.codinbox.echo.api.EchoFuture.completed(java.util.Map.of()));
        CommandSource sender = permittedSender();

        dispatcher(echo).execute(input, sender);

        verify(server).getConnectedUsers();
        verify(sender).sendMessage(any(Component.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"send", "send Alice"})
    void brigadierRoutesIncompleteCommandsToUsageFeedback(String input) throws Exception {
        CommandSource sender = permittedSender();
        var echo = mock(fr.codinbox.echo.api.EchoClient.class);

        dispatcher(echo).execute(input, sender);

        verify(sender).sendMessage(any(Component.class));
        verifyNoInteractions(echo);
    }

    @ParameterizedTest
    @CsvSource({
        "glist,quake (1)",
        "glist --group proxy,proxy-1 (1)",
        "glist server:quake --proxy all --page 1,quake (1): Alice",
        "echo user list --group none --count,Total: 1",
        "echo user list all --group server --page 1,quake (1): Alice",
        "glist --group proxy all,proxy-1 (1): Alice"
    })
    void brigadierRoutesGlistSelectorsAndFlagsWithOptionalSelector(String input, String expected)
            throws Exception {
        var echo = listedNetwork();
        CommandSource sender = permittedSender();
        CompletableFuture<Component> response = responseFrom(sender);

        dispatcher(echo).execute(input, sender);

        assertThat(
                        PlainTextComponentSerializer.plainText()
                                .serialize(response.orTimeout(2, TimeUnit.SECONDS).join()))
                .contains("Total: 1", expected)
                .doesNotContain("ERROR:");
    }

    private static fr.codinbox.echo.api.EchoClient listedNetwork() {
        var echo = mock(fr.codinbox.echo.api.EchoClient.class);
        var server = mock(fr.codinbox.echo.api.server.Server.class);
        var user = mock(fr.codinbox.echo.api.user.User.class);
        var id = new java.util.UUID(0, 1);
        when(echo.getAllUsers())
                .thenReturn(fr.codinbox.echo.api.EchoFuture.completed(java.util.Map.of(id, 1L)));
        when(echo.getUserById(id))
                .thenReturn(fr.codinbox.echo.api.EchoFuture.completed(java.util.Optional.of(user)));
        when(echo.getServerById("quake"))
                .thenReturn(
                        fr.codinbox.echo.api.EchoFuture.completed(java.util.Optional.of(server)));
        when(server.getConnectedUsers())
                .thenReturn(fr.codinbox.echo.api.EchoFuture.completed(java.util.Map.of(id, 1L)));
        when(user.getUsername())
                .thenReturn(
                        fr.codinbox.echo.api.EchoFuture.completed(java.util.Optional.of("Alice")));
        when(user.getCurrentServerId())
                .thenReturn(
                        fr.codinbox.echo.api.EchoFuture.completed(java.util.Optional.of("quake")));
        when(user.getCurrentProxyId())
                .thenReturn(
                        fr.codinbox.echo.api.EchoFuture.completed(
                                java.util.Optional.of("proxy-1")));
        return echo;
    }

    private static CompletableFuture<Component> responseFrom(CommandSource sender) {
        CompletableFuture<Component> response = new CompletableFuture<>();
        doAnswer(
                        call -> {
                            response.complete(call.getArgument(0));
                            return null;
                        })
                .when(sender)
                .sendMessage(any(Component.class));
        return response;
    }

    private static CommandSource permittedSender() {
        CommandSource sender = mock(CommandSource.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        return sender;
    }

    private static com.mojang.brigadier.CommandDispatcher<CommandSource> dispatcher(
            fr.codinbox.echo.api.EchoClient echo) {
        var proxy = mock(com.velocitypowered.api.proxy.ProxyServer.class, RETURNS_DEEP_STUBS);
        var manager =
                new org.incendo.cloud.velocity.VelocityCommandManager<CommandSource>(
                        mock(com.velocitypowered.api.plugin.PluginContainer.class),
                        proxy,
                        org.incendo.cloud.execution.ExecutionCoordinator.simpleCoordinator(),
                        org.incendo.cloud.SenderMapper.identity());
        EchoPlugin.configureCommands(manager);
        var parser =
                new org.incendo.cloud.annotations.AnnotationParser<>(manager, CommandSource.class);
        var commands =
                new fr.codinbox.echo.commands.EchoCommands<>(
                        echo, EchoPlugin.commandAudience(), EchoPlugin.COMMAND_ROOT);
        commands.register(parser);
        commands.registerSend(parser);
        commands.registerGlist(parser);
        var captured =
                org.mockito.ArgumentCaptor.forClass(
                        com.velocitypowered.api.command.BrigadierCommand.class);
        verify(proxy.getCommandManager(), atLeastOnce())
                .register(
                        any(com.velocitypowered.api.command.CommandMeta.class), captured.capture());
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<CommandSource>();
        captured.getAllValues()
                .forEach(command -> dispatcher.getRoot().addChild(command.getNode()));
        return dispatcher;
    }
}
