package fr.codinbox.echo.commands;

import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest.PlayerResponse;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.incendo.cloud.suggestion.Suggestion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest.ServerSwitchRequestStatus.*;
import static fr.codinbox.echo.commands.CommandTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("unit")
class SendCommandsTest {
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final UUID CAROL = new UUID(0, 3);

    @ParameterizedTest
    @CsvSource({"all,,1,1,1", "all,all,1,1,1", "all,local,1,0,1", "all,proxy-2,0,1,0",
            "server:quake,,1,1,0", "server:quake,proxy-2,0,1,0", "current,,1,1,0", "current,local,1,0,0",
            "Alice,,1,0,0", "aLiCe,,1,0,0", "Bob,local,0,0,0", "00000000-0000-0000-0000-000000000002,,0,1,0"})
    void selectsNetworkUsersAndIntersectsSourceProxy(String selector, String proxy, int alice, int bob, int carol) {
        Network network = network();

        network.fixture.commands().userSend(network.fixture.context(), selector, "lobby", proxy).join();

        verify(network.alice, times(alice)).tryConnectToServer("lobby", Duration.ofSeconds(10));
        verify(network.bob, times(bob)).tryConnectToServer("lobby", Duration.ofSeconds(10));
        verify(network.carol, times(carol)).tryConnectToServer("lobby", Duration.ofSeconds(10));
    }

    @Test
    void aggregatesSuccessAlreadyConnectedAndKickReason() {
        Network network = network();

        network.fixture.commands().userSend(network.fixture.context(), "all", "lobby", null).join();

        assertThat(network.fixture.output()).contains("transferred=1 already_connected=1 failed=1",
                "1 x SERVER_DISCONNECTED: Maintenance");
    }

    @Test
    void disconnectedOrTimedOutUsersDoNotAbortOtherTransfers() {
        Network network = network();
        when(network.fixture.echo().getUserById(BOB)).thenReturn(EchoFuture.completed(Optional.empty()));
        when(network.carol.tryConnectToServer(anyString(), any(Duration.class)))
                .thenReturn(echoFailed(new TimeoutException()));

        network.fixture.commands().userSend(network.fixture.context(), "all", "lobby", null).join();

        assertThat(network.fixture.output()).contains("transferred=1 already_connected=0 failed=2",
                "User not found: " + BOB, "Operation timed out.");
    }

    @ParameterizedTest
    @CsvSource({"current,lobby,,current requires a player", "server:missing,lobby,,Server not found: missing",
            "all,missing,,Server not found: missing", "all,lobby,missing,Proxy not found: missing",
            "Missing,lobby,,User not found: Missing"})
    void rejectsInvalidSelectionBeforeAnyTransfer(String selector, String server, String proxy, String error) {
        Network network = network();
        network.fixture.audience().playerId = null;

        network.fixture.commands().userSend(network.fixture.context(), selector, server, proxy).join();

        assertThat(network.fixture.output()).contains(error);
        verify(network.alice, never()).tryConnectToServer(anyString(), any(Duration.class));
        verify(network.bob, never()).tryConnectToServer(anyString(), any(Duration.class));
    }

    @Test
    void rejectsLocalProxyFilterOnPaper() {
        Network network = network();
        when(network.fixture.echo().getCurrentResourceType()).thenReturn(EchoResourceType.SERVER);

        network.fixture.commands().userSend(network.fixture.context(), "all", "lobby", "local").join();

        assertThat(network.fixture.output()).contains("--proxy local requires a proxy");
        verify(network.alice, never()).tryConnectToServer(anyString(), any(Duration.class));
    }

    @Test
    void reportsEmptySelectionWithoutTransfers() {
        Network network = network();

        network.fixture.commands().userSend(network.fixture.context(), "Bob", "lobby", "local").join();

        assertThat(network.fixture.output()).contains("transferred=0 already_connected=0 failed=0",
                "No users matched the source selection.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"velocity.command.send", "echo.command.user.send"})
    void bothPermissionsAllowBothCommandPathsAndFlags(String permission) {
        Network network = network();
        TestManager manager = manager(network.fixture);

        manager.commandExecutor().executeCommand(permission, "send all lobby --proxy proxy-2").join();
        manager.commandExecutor().executeCommand(permission, "echoproxy user send all lobby --proxy local").join();

        verify(network.alice).tryConnectToServer("lobby", Duration.ofSeconds(10));
        verify(network.bob).tryConnectToServer("lobby", Duration.ofSeconds(10));
        verify(network.carol).tryConnectToServer("lobby", Duration.ofSeconds(10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"send all lobby", "echo user send all lobby"})
    void missingPermissionPreventsRouting(String command) {
        Network network = network();
        TestManager manager = manager(network.fixture);

        assertThatThrownBy(() -> manager.commandExecutor().executeCommand("none", command).join())
                .hasRootCauseInstanceOf(org.incendo.cloud.exception.NoPermissionException.class);
        verify(network.alice, never()).tryConnectToServer(anyString(), any(Duration.class));
    }

    @Test
    void suggestsNetworkNamesSelectorsServersAndProxyFlagsThroughParser() {
        Network network = network();
        TestManager manager = manager(network.fixture);

        var targets = manager.suggestionFactory().suggest("velocity.command.send", "send ").join();
        var servers = manager.suggestionFactory().suggest("velocity.command.send", "send all ").join();
        var proxies = manager.suggestionFactory().suggest("velocity.command.send", "send all lobby --proxy ").join();

        assertThat(targets.list()).extracting(Suggestion::suggestion).contains("Alice", "Bob", "all", "current", "server:quake");
        assertThat(servers.list()).extracting(Suggestion::suggestion).contains("lobby", "quake");
        assertThat(proxies.list()).extracting(Suggestion::suggestion).contains("all", "local", "proxy-1", "proxy-2");
    }

    @Test
    void suggestsMatchingUserBeyondFirstHundredNetworkMembers() {
        Network network = network();
        var members = java.util.stream.LongStream.rangeClosed(1, 101).boxed().collect(
                java.util.stream.Collectors.toMap(id -> new UUID(0, id), id -> 1L));
        User zelda = user(new UUID(0, 101), "Zelda", SUCCESS);
        when(network.fixture.echo().getAllUsers()).thenReturn(EchoFuture.completed(members));
        when(network.fixture.echo().getUserById(new UUID(0, 101)))
                .thenReturn(EchoFuture.completed(Optional.of(zelda)));

        var targets = manager(network.fixture).suggestionFactory().suggest("velocity.command.send", "send Zel").join();

        assertThat(targets.list()).extracting(Suggestion::suggestion).containsExactly("Zelda");
    }

    @ParameterizedTest
    @ValueSource(strings = {"send all resource-199", "send server:resource-199", "send all lobby --proxy resource-199"})
    void suggestsMatchingResourceBeyondFirstHundred(String input) {
        Network network = network();
        var resources = java.util.stream.IntStream.range(0, 200).boxed().collect(
                java.util.stream.Collectors.toMap(id -> "resource-" + id, id -> 1L));
        when(network.fixture.echo().getServers()).thenReturn(EchoFuture.completed(resources));
        when(network.fixture.echo().getProxies()).thenReturn(EchoFuture.completed(resources));

        var targets = manager(network.fixture).suggestionFactory().suggest("velocity.command.send", input).join();

        assertThat(targets.list()).extracting(Suggestion::suggestion).hasSize(1);
        assertThat(targets.list().getFirst().suggestion()).endsWith("resource-199");
    }

    private static Network network() {
        Fixture fixture = fixture();
        User alice = user(ALICE, "Alice", SUCCESS);
        User bob = user(BOB, "Bob", ALREADY_CONNECTED);
        User carol = user(CAROL, "Carol", SERVER_DISCONNECTED);
        Map<UUID, User> users = Map.of(ALICE, alice, BOB, bob, CAROL, carol);
        Map<String, User> names = Map.of("alice", alice, "bob", bob, "carol", carol);
        Map<String, Server> servers = Map.of("lobby", server(Map.of(CAROL, 1L)), "quake", server(Map.of(ALICE, 1L, BOB, 1L)));
        Map<String, Proxy> proxies = Map.of("proxy-1", proxy(Map.of(ALICE, 1L, CAROL, 1L)), "proxy-2", proxy(Map.of(BOB, 1L)));
        when(fixture.echo().getAllUsers()).thenReturn(EchoFuture.completed(Map.of(ALICE, 1L, BOB, 1L, CAROL, 1L)));
        when(fixture.echo().getUserById(any())).thenAnswer(call -> EchoFuture.completed(Optional.ofNullable(users.get(call.getArgument(0)))));
        when(fixture.echo().getUserByUsername(anyString())).thenAnswer(call -> EchoFuture.completed(Optional.ofNullable(
                names.get(call.<String>getArgument(0).toLowerCase(java.util.Locale.ROOT)))));
        when(fixture.echo().getServerById(anyString())).thenAnswer(call -> EchoFuture.completed(Optional.ofNullable(servers.get(call.getArgument(0)))));
        when(fixture.echo().getProxyById(anyString())).thenAnswer(call -> EchoFuture.completed(Optional.ofNullable(proxies.get(call.getArgument(0)))));
        when(fixture.echo().getServers()).thenReturn(EchoFuture.completed(Map.of("lobby", 1L, "quake", 1L)));
        when(fixture.echo().getProxies()).thenReturn(EchoFuture.completed(Map.of("proxy-1", 1L, "proxy-2", 1L)));
        when(fixture.echo().getCurrentResourceType()).thenReturn(EchoResourceType.PROXY);
        when(fixture.echo().getCurrentResourceId()).thenReturn(Optional.of("proxy-1"));
        fixture.audience().playerId = ALICE;
        return new Network(fixture, alice, bob, carol);
    }

    private static User user(UUID id, String name, fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest.ServerSwitchRequestStatus status) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getUsername()).thenReturn(EchoFuture.completed(Optional.of(name)));
        when(user.getCurrentServerId()).thenReturn(EchoFuture.completed(Optional.of("quake")));
        when(user.tryConnectToServer(anyString(), any(Duration.class))).thenReturn(EchoFuture.completed(
                new PlayerResponse(status == SUCCESS, status, status == SERVER_DISCONNECTED ? "{\"text\":\"Maintenance\"}" : null)));
        return user;
    }

    private static Server server(Map<UUID, Long> members) {
        Server server = mock(Server.class);
        when(server.getConnectedUsers()).thenReturn(EchoFuture.completed(members));
        return server;
    }

    private static Proxy proxy(Map<UUID, Long> members) {
        Proxy proxy = mock(Proxy.class);
        when(proxy.getConnectedUsers()).thenReturn(EchoFuture.completed(members));
        return proxy;
    }

    private static TestManager manager(Fixture fixture) {
        TestManager manager = new TestManager();
        EchoCommands<String> commands = new EchoCommands<>(fixture.echo(), fixture.audience(), "echo|echoproxy");
        AnnotationParser<String> parser = new AnnotationParser<>(manager, String.class);
        commands.register(parser);
        commands.registerSend(parser);
        return manager;
    }

    private static final class TestManager extends CommandManager<String> {
        TestManager() {
            super(ExecutionCoordinator.simpleCoordinator(), CommandRegistrationHandler.nullCommandRegistrationHandler());
        }

        @Override
        public boolean hasPermission(String sender, String permission) {
            return sender.equals(permission);
        }
    }

    private record Network(Fixture fixture, User alice, User bob, User carol) {}
}
