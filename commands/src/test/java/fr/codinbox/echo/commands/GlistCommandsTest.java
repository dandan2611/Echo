package fr.codinbox.echo.commands;

import static fr.codinbox.echo.commands.CommandTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.user.User;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
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

@Tag("unit")
class GlistCommandsTest {
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final UUID CAROL = new UUID(0, 3);

    @Test
    void defaultViewCountsUsersAcrossProxiesByServer() {
        Network network = network();

        network.run("echo user list");

        assertThat(network.fixture.output())
                .contains("Total: 3", "lobby (1)", "quake (2)")
                .doesNotContain("Alice", "Bob", "Carol");
    }

    @ParameterizedTest
    @CsvSource({
        "all,3,Carol|Alice|Bob",
        "all --proxy all,3,Carol|Alice|Bob",
        "all --proxy proxy-2,1,Bob",
        "all --proxy local,2,Carol|Alice",
        "server:quake,2,Alice|Bob",
        "quake,2,Alice|Bob",
        "current,2,Alice|Bob",
        "server:quake --proxy proxy-2,1,Bob",
        "current --proxy local,1,Alice"
    })
    void selectorsListNetworkUsersIntersectedWithProxy(String selection, int total, String names) {
        Network network = network();

        network.run("glist " + selection);

        assertThat(network.fixture.output()).contains("Total: " + total);
        assertThat(java.util.regex.Pattern.compile("Alice|Bob|Carol")
                        .matcher(network.fixture.output())
                        .results()
                        .map(java.util.regex.MatchResult::group))
                .containsExactly(names.split("\\|"));
    }

    @ParameterizedTest
    @CsvSource({
        "--group proxy,proxy-1 (2)|proxy-2 (1)",
        "--group server,lobby (1)|quake (2)",
        "all --group proxy --count,proxy-1 (2)|proxy-2 (1)",
        "all --group none --count,Total: 3"
    })
    void summaryAndCountGroupWithoutNames(String options, String lines) {
        Network network = network();

        network.run("glist " + options);

        assertThat(network.fixture.output())
                .contains("Total: 3")
                .containsSubsequence(lines.split("\\|"))
                .doesNotContain("Alice", "Bob", "Carol");
    }

    @ParameterizedTest
    @CsvSource({
        "all --group proxy,'proxy-1 (2): Alice, Carol|proxy-2 (1): Bob'",
        "all --group none,'Alice, Bob, Carol'",
        "server:quake --group proxy --proxy proxy-2,proxy-2 (1): Bob"
    })
    void groupingOnlyChangesPresentation(String options, String lines) {
        Network network = network();

        network.run("glist " + options);

        assertThat(network.fixture.output()).containsSubsequence(lines.split("\\|"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"glist", "echo user list", "echoproxy user list", "echoserver user list"})
    void detailPagesRetainTotalsAndLinkBackWithTheSameSelection(String command) {
        Network network = largeNetwork(false);

        network.run(command + " all --proxy proxy-1 --group server --page 2");

        assertThat(network.fixture.output())
                .contains("Total: 21", "Page 2/2", "quake (21): Player21")
                .doesNotContain("Player01", "Player20");
        assertThat(network.fixture.audience().messages().stream().flatMap(GlistCommandsTest::clicks))
                .containsExactly(ClickEvent.runCommand("/" + command + " all --proxy proxy-1 --group server --page 1"));
    }

    @Test
    void summaryPagesBoundGroupsAndKeepCountModeInNavigation() {
        Network network = largeNetwork(true);

        network.run("glist all --count --page 2");

        assertThat(network.fixture.output())
                .contains("Total: 21", "Page 2/2", "server-21 (1)")
                .doesNotContain("server-01", "server-20", "Player21");
        assertThat(network.fixture.audience().messages().stream().flatMap(GlistCommandsTest::clicks))
                .containsExactly(ClickEvent.runCommand("/glist all --count --page 1"));
    }

    @Test
    void firstPageHasTwentyNamesAndNextLink() {
        Network network = largeNetwork(false);

        network.run("glist all --group none");

        assertThat(network.fixture.output())
                .contains("Total: 21", "Page 1/2", "Player01", "Player20")
                .doesNotContain("Player21");
        assertThat(java.util.regex.Pattern.compile("Player\\d+")
                        .matcher(network.fixture.output())
                        .results())
                .hasSize(20);
        assertThat(network.fixture.audience().messages().stream().flatMap(GlistCommandsTest::clicks))
                .containsExactly(ClickEvent.runCommand("/glist all --group none --page 2"));
    }

    @ParameterizedTest
    @CsvSource({
        "0,Glist: --page must be at least 1.",
        "-1,Glist: --page must be at least 1.",
        "3,Glist: page 3 is out of range (1-2).",
        "2147483647,Glist: page 2147483647 is out of range (1-2)."
    })
    void invalidPageExplainsTheAllowedRange(int page, String error) {
        Network network = largeNetwork(false);

        network.run("glist all --page " + page);

        assertThat(network.fixture.output()).contains(error).doesNotContain("Total:");
    }

    @Test
    void missingInitialUsernameRetainsUuidAndTotalWithPartialWarning() {
        Network network = network();
        when(network.users.get(BOB).getUsername()).thenReturn(EchoFuture.completed(Optional.empty()));

        network.run("glist all");

        assertThat(network.fixture.output())
                .contains(
                        "Total: 3",
                        "(unknown server) (1): " + BOB,
                        "Partial results: details unavailable for 1 user(s).",
                        "Alice",
                        "Carol");
    }

    @Test
    void failedNameRereadPreservesKnownGroupAndOtherUsers() {
        Network network = network();
        when(network.users.get(BOB).getUsername())
                .thenReturn(EchoFuture.completed(Optional.of("Bob")))
                .thenReturn(echoFailed(new TimeoutException()));

        network.run("glist all");

        assertThat(network.fixture.output())
                .contains(
                        "Total: 3",
                        "quake (2): " + BOB + ", Alice",
                        "Carol",
                        "Partial results: details unavailable for 1 user(s).")
                .doesNotContain("ERROR:");
    }

    @Test
    void failedGroupReadPreservesKnownNameAndSignalsUnknownGroup() {
        Network network = network();
        when(network.users.get(BOB).getCurrentProxyId())
                .thenReturn(echoFailed(new IllegalStateException("Redis down")));

        network.run("glist all --group proxy");

        assertThat(network.fixture.output())
                .contains(
                        "Total: 3",
                        "(unknown proxy) (1): Bob",
                        "proxy-1 (2): Alice, Carol",
                        "Partial results: details unavailable for 1 user(s).")
                .doesNotContain("Redis down", "ERROR:");
    }

    @Test
    void loginTransitionIsDistinctFromFailedGroupReads() {
        Network network = network();
        network.users.put(BOB, user(BOB, "Bob", null, "proxy-2"));

        network.run("glist all");

        assertThat(network.fixture.output())
                .contains("Total: 3", "(no server) (1): Bob")
                .doesNotContain("(unknown server)", "Partial results");
    }

    @Test
    void countWithoutGroupingNeedsOnlyMembership() {
        Network network = network();
        when(network.fixture.echo().getUserById(any())).thenReturn(echoFailed(new IllegalStateException("Redis down")));

        network.run("glist --group none --count");

        assertThat(network.fixture.output()).contains("Total: 3").doesNotContain("Partial results", "ERROR:");
    }

    @Test
    void detailReadsHaveBoundedConcurrency() {
        Network network = largeNetwork(false, 100);
        GatedUsers gate = gateDetails(network);

        CompletableFuture<?> command =
                network.manager.commandExecutor().executeCommand("echo.command.user.list", "glist all");
        gate.firstBatch.orTimeout(2, TimeUnit.SECONDS).join();
        gate.release.complete(null);
        command.orTimeout(3, TimeUnit.SECONDS).join();

        assertThat(gate.maximum.get()).isBetween(2, 32);
        assertThat(network.fixture.output()).contains("Total: 100").doesNotContain("Partial results");
    }

    @Test
    void stalledDetailReadsReturnPartialResultsWithinTheDeadline() {
        Network network = largeNetwork(false, 40);
        when(network.fixture.echo().getUserById(any())).thenReturn(new EchoFuture<>());

        network.manager
                .commandExecutor()
                .executeCommand("echo.command.user.list", "glist all")
                .orTimeout(12, TimeUnit.SECONDS)
                .join();

        assertThat(network.fixture.output())
                .contains("Total: 40", "(unknown server) (40)", "Partial results: details unavailable for 40 user(s).");
    }

    @Test
    void completesSelectorsAndNetworkResourcesAndGroupingFlags() {
        Network network = network();

        var selectors = network.manager
                .suggestionFactory()
                .suggest("velocity.command.glist", "glist ")
                .join();
        var proxies = network.manager
                .suggestionFactory()
                .suggest("velocity.command.glist", "glist --proxy ")
                .join();
        var groups = network.manager
                .suggestionFactory()
                .suggest("echo.command.user.list", "echo user list all --group ")
                .join();

        assertThat(selectors.list())
                .extracting(Suggestion::suggestion)
                .contains("all", "current", "server:quake", "quake", "lobby");
        assertThat(proxies.list()).extracting(Suggestion::suggestion).contains("all", "local", "proxy-1", "proxy-2");
        assertThat(groups.list())
                .extracting(Suggestion::suggestion)
                .containsExactlyInAnyOrder("server", "proxy", "none");
    }

    @ParameterizedTest
    @CsvSource({
        "glist server:resource-199,server:resource-199",
        "glist resource-199,resource-199|server:resource-199",
        "echo user list --proxy resource-199,resource-199"
    })
    void completionIncludesResourcesBeyondTheFirstHundred(String input, String expected) {
        Network network = network();
        var resources = java.util.stream.IntStream.range(0, 200)
                .boxed()
                .collect(java.util.stream.Collectors.toMap(id -> "resource-" + id, id -> 1L));
        when(network.fixture.echo().getServers()).thenReturn(EchoFuture.completed(resources));
        when(network.fixture.echo().getProxies()).thenReturn(EchoFuture.completed(resources));

        var suggestions = network.manager
                .suggestionFactory()
                .suggest("echo.command.user.list", input)
                .join();

        assertThat(suggestions.list())
                .extracting(Suggestion::suggestion)
                .containsExactlyInAnyOrder(expected.split("\\|"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"velocity.command.glist", "echo.command.user.list"})
    void eitherPermissionGrantsAccessToBothCommandPaths(String permission) {
        Network network = network();

        network.manager
                .commandExecutor()
                .executeCommand(permission, "glist server:quake --proxy proxy-2")
                .join();
        network.manager
                .commandExecutor()
                .executeCommand(permission, "echoproxy user list current --proxy local")
                .join();

        assertThat(network.fixture.output())
                .contains("quake (1): Bob", "quake (1): Alice")
                .doesNotContain("Carol");
    }

    @ParameterizedTest
    @ValueSource(strings = {"glist all", "echo user list", "echoproxy user list", "echoserver user list"})
    void missingPermissionPreventsListing(String command) {
        Network network = network();

        assertThatThrownBy(() -> network.manager
                        .commandExecutor()
                        .executeCommand("none", command)
                        .join())
                .hasRootCauseInstanceOf(org.incendo.cloud.exception.NoPermissionException.class);
        assertThat(network.fixture.output()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "server:missing,Server not found: missing",
        "missing,Server not found: missing",
        "all --proxy missing,Proxy not found: missing",
        "--group zone,'Glist: --group must be server, proxy or none.'"
    })
    void invalidResourcesAndGroupingAreErrorsInsteadOfEmptySelections(String arguments, String error) {
        Network network = network();

        network.run("glist " + arguments);

        assertThat(network.fixture.output()).contains("ERROR: " + error).doesNotContain("Total:");
    }

    @ParameterizedTest
    @ValueSource(strings = {"server:empty", "server:lobby --proxy proxy-2"})
    void validEmptySelectionIsExplicit(String selection) {
        Network network = network();

        network.run("glist " + selection);

        assertThat(network.fixture.output())
                .contains("Total: 0", "No users matched the selection.")
                .doesNotContain("ERROR:");
    }

    @Test
    void consoleCanUseLocalProxyButCurrentRequiresAPlayer() {
        Network network = network();
        network.fixture.audience().playerId = null;

        network.run("glist all --proxy local");
        network.run("glist current");

        assertThat(network.fixture.output())
                .contains(
                        "Total: 2", "Alice", "Carol", "Glist: current requires a player; use server:<id> from console.")
                .doesNotContain("Bob");
    }

    @Test
    void currentRequiresAConnectedBackend() {
        Network network = network();
        when(network.users.get(ALICE).getCurrentServerId()).thenReturn(EchoFuture.completed(Optional.empty()));

        network.run("glist current");

        assertThat(network.fixture.output())
                .contains("Glist: you are not connected to a server.")
                .doesNotContain("Total:");
    }

    @Test
    void localProxyIsUnavailableOnPaperInExecutionAndCompletion() {
        Network network = network();
        when(network.fixture.echo().getCurrentResourceType()).thenReturn(EchoResourceType.SERVER);

        network.run("echoserver user list all --proxy local");
        var suggestions = network.manager
                .suggestionFactory()
                .suggest("echo.command.user.list", "echoserver user list --proxy ")
                .join();

        assertThat(network.fixture.output())
                .contains("Glist: --proxy local requires a proxy")
                .doesNotContain("Total:");
        assertThat(suggestions.list())
                .extracting(Suggestion::suggestion)
                .contains("all", "proxy-1", "proxy-2")
                .doesNotContain("local");
    }

    @Test
    void membershipFailureCannotBeMistakenForZeroPlayers() {
        Network network = network();
        when(network.fixture.echo().getAllUsers()).thenReturn(echoFailed(new TimeoutException()));

        network.run("glist");

        assertThat(network.fixture.output())
                .contains("ERROR: Operation timed out")
                .doesNotContain("Total:");
    }

    @Test
    void switchingUsersCountOnceDespiteOverlappingProxyMemberships() {
        Network network = network();
        when(network.proxies.get("proxy-2").getConnectedUsers())
                .thenReturn(EchoFuture.completed(Map.of(ALICE, 1L, BOB, 1L)));

        network.run("glist all --group proxy");

        assertThat(network.fixture.output()).contains("Total: 3", "proxy-1 (2): Alice, Carol", "proxy-2 (1): Bob");
    }

    private static GatedUsers gateDetails(Network network) {
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Integer> firstBatch = new CompletableFuture<>();
        AtomicInteger pending = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(network.fixture.echo().getUserById(any())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            EchoFuture<Optional<User>> result = new EchoFuture<>();
            int active = pending.incrementAndGet();
            maximum.accumulateAndGet(active, Math::max);
            if (active == 32) firstBatch.complete(active);
            release.thenRun(() -> {
                pending.decrementAndGet();
                result.complete(Optional.of(network.users.get(id)));
            });
            return result;
        });
        return new GatedUsers(release, firstBatch, maximum);
    }

    private record GatedUsers(
            CompletableFuture<Void> release, CompletableFuture<Integer> firstBatch, AtomicInteger maximum) {}

    private static Stream<ClickEvent> clicks(Component component) {
        return Stream.concat(
                Stream.ofNullable(component.clickEvent()),
                component.children().stream().flatMap(GlistCommandsTest::clicks));
    }

    private static Network largeNetwork(boolean manyServers) {
        return largeNetwork(manyServers, 21);
    }

    private static Network largeNetwork(boolean manyServers, int count) {
        Network network = network();
        network.users.clear();
        for (int index = count; index > 0; index--) {
            UUID id = new UUID(0, index);
            network.users.put(
                    id,
                    user(
                            id,
                            "Player%02d".formatted(index),
                            manyServers ? "server-%02d".formatted(index) : "quake",
                            "proxy-1"));
        }
        when(network.proxies.get("proxy-1").getConnectedUsers())
                .thenReturn(EchoFuture.completed(network.users.keySet().stream()
                        .collect(java.util.stream.Collectors.toMap(id -> id, id -> 1L))));
        return network;
    }

    private static Network network() {
        Fixture fixture = fixture();
        Map<UUID, User> users = new LinkedHashMap<>();
        users.put(ALICE, user(ALICE, "Alice", "quake", "proxy-1"));
        users.put(BOB, user(BOB, "Bob", "quake", "proxy-2"));
        users.put(CAROL, user(CAROL, "Carol", "lobby", "proxy-1"));
        Map<String, Server> servers = Map.of(
                "lobby",
                server(Map.of(CAROL, 1L)),
                "quake",
                server(Map.of(ALICE, 1L, BOB, 1L)),
                "empty",
                server(Map.of()));
        Map<String, Proxy> proxies =
                Map.of("proxy-1", proxy(Map.of(ALICE, 1L, CAROL, 1L)), "proxy-2", proxy(Map.of(BOB, 1L)));
        when(fixture.echo().getAllUsers())
                .thenAnswer(call -> EchoFuture.completed(
                        users.keySet().stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> 1L))));
        when(fixture.echo().getUserById(any())).thenAnswer(call -> {
            User user = users.get(call.getArgument(0));
            // EchoClientImpl resolves the record only when its initial username
            // read succeeds.
            return user == null
                    ? EchoFuture.completed(Optional.empty())
                    : EchoFuture.of(user.getUsername().thenApply(name -> name.map(ignored -> user)));
        });
        when(fixture.echo().getServerById(anyString()))
                .thenAnswer(call -> EchoFuture.completed(Optional.ofNullable(servers.get(call.getArgument(0)))));
        when(fixture.echo().getProxyById(anyString()))
                .thenAnswer(call -> EchoFuture.completed(Optional.ofNullable(proxies.get(call.getArgument(0)))));
        when(fixture.echo().getServers())
                .thenReturn(EchoFuture.completed(Map.of("lobby", 1L, "quake", 1L, "empty", 1L)));
        when(fixture.echo().getProxies()).thenReturn(EchoFuture.completed(Map.of("proxy-1", 1L, "proxy-2", 1L)));
        when(fixture.echo().getCurrentResourceType()).thenReturn(EchoResourceType.PROXY);
        when(fixture.echo().getCurrentResourceId()).thenReturn(Optional.of("proxy-1"));
        fixture.audience().playerId = ALICE;
        TestManager manager = new TestManager();
        EchoCommands<String> commands =
                new EchoCommands<>(fixture.echo(), fixture.audience(), "echo|echoproxy|echoserver");
        AnnotationParser<String> parser = new AnnotationParser<>(manager, String.class);
        commands.register(parser);
        commands.registerGlist(parser);
        return new Network(fixture, manager, users, servers, proxies);
    }

    private static User user(UUID id, String name, String server, String proxy) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getUsername()).thenReturn(EchoFuture.completed(Optional.ofNullable(name)));
        when(user.getCurrentServerId()).thenReturn(EchoFuture.completed(Optional.ofNullable(server)));
        when(user.getCurrentProxyId()).thenReturn(EchoFuture.completed(Optional.ofNullable(proxy)));
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

    private static final class TestManager extends CommandManager<String> {
        TestManager() {
            super(
                    ExecutionCoordinator.simpleCoordinator(),
                    CommandRegistrationHandler.nullCommandRegistrationHandler());
        }

        @Override
        public boolean hasPermission(String sender, String permission) {
            return sender.equals(permission);
        }
    }

    private record Network(
            Fixture fixture,
            TestManager manager,
            Map<UUID, User> users,
            Map<String, Server> servers,
            Map<String, Proxy> proxies) {
        void run(String command) {
            this.manager
                    .commandExecutor()
                    .executeCommand("echo.command.user.list", command)
                    .join();
        }
    }
}
