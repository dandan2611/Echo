package fr.codinbox.echo.commands;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.impl.ResourceControlRequest;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.api.messaging.impl.UserDisconnectRequest;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerAvailability;
import fr.codinbox.echo.api.server.ServerLoad;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import fr.codinbox.echo.api.user.User;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.string.StringProcessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static fr.codinbox.echo.commands.CommandTestSupport.controlResponse;
import static fr.codinbox.echo.commands.CommandTestSupport.disconnectResponse;
import static fr.codinbox.echo.commands.CommandTestSupport.echoFailed;
import static fr.codinbox.echo.commands.CommandTestSupport.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class EchoCommandsTest {

    @Test
    void everyCommandHasAPermissionAndUsesTheRootPlaceholder() {
        List<Method> commands = Arrays.stream(EchoCommands.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Command.class)).toList();

        assertThat(commands).allSatisfy(method -> {
            Permission permission = method.getAnnotation(Permission.class);
            assertThat(permission).isNotNull();
            assertThat(permission.value()).singleElement().asString()
                    .isNotBlank().startsWith("echo.command.");
            assertThat(method.getAnnotation(Command.class).value()).startsWith("${root}");
            assertThat(method.getReturnType()).isEqualTo(CompletableFuture.class);
        });
        assertThat(commands.stream().map(method -> method.getAnnotation(Permission.class).value()[0]))
                .doesNotHaveDuplicates();
    }

    @Test
    void registersAliasesAndRejectsInvalidRoots() {
        CommandTestSupport.Fixture fixture = fixture();
        @SuppressWarnings("unchecked")
        AnnotationParser<String> parser = mock(AnnotationParser.class);
        when(parser.stringProcessor()).thenReturn(input -> "before " + input);
        when(parser.parse(any(Object[].class))).thenReturn(List.of());

        assertThat(fixture.commands().register(parser)).isEmpty();
        ArgumentCaptor<StringProcessor> processor = ArgumentCaptor.forClass(StringProcessor.class);
        verify(parser).stringProcessor(processor.capture());
        assertThat(processor.getValue().processString("${root} status"))
                .isEqualTo("before echo|echoserver status");

        EchoClient echo = mock(EchoClient.class);
        CommandAudience<String> audience = mock(CommandAudience.class);
        assertThat(new EchoCommands<>(echo, audience, "echo")).isNotNull();
        assertThatThrownBy(() -> new EchoCommands<>(echo, audience, "echo bad"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rootSyntax");
        assertThatThrownBy(() -> new EchoCommands<>(echo, audience, null))
                .isInstanceOf(NullPointerException.class).hasMessage("rootSyntax");
    }

    @Test
    void rootHelpVersionAndStatusRenderBothLocalIdStates() {
        CommandTestSupport.Fixture fixture = fixture();
        when(fixture.echo().getServers()).thenReturn(EchoFuture.completed(Map.of("server-1", 1L)));
        when(fixture.echo().getProxies()).thenReturn(EchoFuture.completed(Map.of("proxy-2", 2L, "proxy-1", 1L)));
        when(fixture.echo().getAllUsers()).thenReturn(EchoFuture.completed(Map.of(UUID.randomUUID(), 1L)));
        when(fixture.echo().getCurrentResourceType()).thenReturn(EchoResourceType.SERVER);
        when(fixture.echo().getCurrentResourceId()).thenReturn(Optional.of("server-1"), Optional.empty());

        fixture.commands().root(fixture.context()).join();
        fixture.commands().help(fixture.context()).join();
        fixture.commands().version(fixture.context()).join();
        fixture.commands().status(fixture.context()).join();
        fixture.commands().status(fixture.context()).join();

        assertThat(fixture.output()).contains(
                "Echo administration is available. Use echo help.",
                "Echo commands", "status and monitoring", "Echo version", "version:",
                "servers: 1", "proxies: 2", "users: 1", "local id: server-1", "local id: unconfigured");
    }

    @Test
    void monitorsResourcesAndEveryHealthOutcome() {
        CommandTestSupport.Fixture fixture = fixture();
        Server alive = mock(Server.class);
        Server dead = mock(Server.class);
        Proxy proxy = mock(Proxy.class);
        when(fixture.echo().getServers()).thenReturn(EchoFuture.completed(
                Map.of("z-dead", 2L, "a-alive", 1L, "server-missing", 5L)));
        when(fixture.echo().getProxies()).thenReturn(EchoFuture.completed(Map.of("proxy-missing", 3L, "proxy-dead", 4L)));
        when(fixture.echo().getAllUsers()).thenReturn(EchoFuture.completed(Map.of(UUID.randomUUID(), 1L)));
        when(fixture.echo().getServerById("a-alive")).thenReturn(EchoFuture.completed(Optional.of(alive)));
        when(fixture.echo().getServerById("z-dead")).thenReturn(EchoFuture.completed(Optional.of(dead)));
        when(fixture.echo().getServerById("server-missing")).thenReturn(EchoFuture.completed(Optional.empty()));
        when(alive.stillExists()).thenReturn(EchoFuture.completed(true));
        when(dead.stillExists()).thenReturn(EchoFuture.completed(false));
        when(fixture.echo().getProxyById("proxy-dead")).thenReturn(EchoFuture.completed(Optional.of(proxy)));
        when(fixture.echo().getProxyById("proxy-missing")).thenReturn(EchoFuture.completed(Optional.empty()));
        when(proxy.stillExists()).thenReturn(EchoFuture.completed(false));

        fixture.commands().monitorResources(fixture.context()).join();
        fixture.commands().monitorHealth(fixture.context()).join();

        assertThat(fixture.output()).contains("server a-alive 1970-01-01T00:00:00.001Z",
                "proxy proxy-missing 1970-01-01T00:00:00.003Z", "users 1",
                "server a-alive: alive", "server z-dead: missing",
                "server server-missing: missing",
                "proxy proxy-dead: missing", "proxy proxy-missing: missing");
    }

    @Test
    void resourceListsAndInfoCoverEmptyPresentAndMissingLookups() {
        CommandTestSupport.Fixture fixture = fixture();
        Server server = mock(Server.class);
        Proxy proxy = mock(Proxy.class);
        UUID userId = UUID.randomUUID();
        when(fixture.echo().getServers()).thenReturn(EchoFuture.completed(Map.of()),
                EchoFuture.completed(Map.of("server-2", 2L, "server-1", 1L)));
        when(fixture.echo().getProxies()).thenReturn(EchoFuture.completed(Map.of()),
                EchoFuture.completed(Map.of("proxy-1", 3L)));
        when(fixture.echo().getAllUsers()).thenReturn(EchoFuture.completed(Map.of()),
                EchoFuture.completed(Map.of(userId, 4L)));
        when(fixture.echo().getServerById("server-1")).thenReturn(EchoFuture.completed(Optional.of(server)));
        when(fixture.echo().getServerById("missing")).thenReturn(EchoFuture.completed(Optional.empty()));
        when(server.getId()).thenReturn("server-1");
        when(server.getAddress()).thenAnswer(ignored -> {
            assertThat(Thread.currentThread().isVirtual()).isTrue();
            return new Address("127.0.0.1", 25565);
        });
        when(server.getConnectedUsers()).thenReturn(EchoFuture.completed(Map.of(userId, 1L)));
        when(server.getAvailability()).thenReturn(EchoFuture.completed(ServerAvailability.ACTIVE));
        when(fixture.echo().getProxyById("proxy-1")).thenReturn(EchoFuture.completed(Optional.of(proxy)));
        when(fixture.echo().getProxyById("missing")).thenReturn(EchoFuture.completed(Optional.empty()));
        when(proxy.getId()).thenReturn("proxy-1");
        when(proxy.getAddress()).thenAnswer(ignored -> {
            assertThat(Thread.currentThread().isVirtual()).isTrue();
            return new Address("localhost", 25577);
        });
        when(proxy.getConnectedUsers()).thenReturn(EchoFuture.completed(Map.of()));

        fixture.commands().serverList(fixture.context()).join();
        fixture.commands().serverList(fixture.context()).join();
        fixture.commands().proxyList(fixture.context()).join();
        fixture.commands().proxyList(fixture.context()).join();
        fixture.commands().userList(fixture.context()).join();
        fixture.commands().userList(fixture.context()).join();
        fixture.commands().serverInfo(fixture.context(), "server-1").join();
        fixture.commands().serverInfo(fixture.context(), "missing").join();
        fixture.commands().proxyInfo(fixture.context(), "proxy-1").join();
        fixture.commands().proxyInfo(fixture.context(), "missing").join();

        assertThat(fixture.output()).contains("Servers\nNone", "server-1 1970-01-01T00:00:00.001Z",
                "Proxies\nNone", "Users\nNone", userId.toString(), "address: 127.0.0.1:25565",
                "availability: ACTIVE", "address: localhost:25577", "ERROR: Server not found: missing",
                "ERROR: Proxy not found: missing");
        verify(server).getAddress();
        verify(proxy).getAddress();
    }

    @Test
    void propertiesCoverEmptyMissingValuesAndEveryTtlForm() {
        CommandTestSupport.Fixture fixture = fixture();
        Server server = mock(Server.class);
        Proxy proxy = mock(Proxy.class);
        when(fixture.echo().getServerById("server")).thenReturn(EchoFuture.completed(Optional.of(server)));
        when(fixture.echo().getProxyById("proxy")).thenReturn(EchoFuture.completed(Optional.of(proxy)));
        when(server.getPropertiesKeys()).thenReturn(EchoFuture.completed(Set.of()),
                EchoFuture.completed(Set.of("mode", "orphan")));
        when(server.<Object>getProperty("mode")).thenReturn(EchoFuture.completed(Optional.of("ranked")));
        when(server.<Object>getProperty("orphan")).thenReturn(EchoFuture.completed(Optional.empty()));
        when(server.getPropertyTimeToLive("mode")).thenReturn(EchoFuture.completed(15_000L));
        when(server.getPropertyTimeToLive("orphan")).thenReturn(EchoFuture.completed(-2L));
        when(proxy.getPropertiesKeys()).thenReturn(EchoFuture.completed(Set.of("permanent")));
        when(proxy.<Object>getProperty("permanent")).thenReturn(EchoFuture.completed(Optional.of(7)));
        when(proxy.getPropertyTimeToLive("permanent")).thenReturn(EchoFuture.completed(-1L));
        when(proxy.<Object>getProperty("missing")).thenReturn(EchoFuture.completed(Optional.empty()));
        when(proxy.getPropertyTimeToLive("missing")).thenReturn(EchoFuture.completed(-2L));

        fixture.commands().serverProperties(fixture.context(), "server").join();
        fixture.commands().serverProperties(fixture.context(), "server").join();
        fixture.commands().serverProperty(fixture.context(), "server", "mode").join();
        fixture.commands().proxyProperties(fixture.context(), "proxy").join();
        fixture.commands().proxyProperty(fixture.context(), "proxy", "missing").join();

        assertThat(fixture.output()).contains("Server properties\nNone", "mode=ranked ttl=PT15S",
                "orphan=<missing> ttl=missing", "key: mode", "value: ranked", "ttl: PT15S",
                "permanent=7 ttl=none", "ERROR: Property not found: missing");
        verify(server, never()).setProperty(anyString(), any());
    }

    @Test
    void serverLoadCoversMissingFreshAndStaleSnapshots() {
        CommandTestSupport.Fixture fixture = fixture();
        Server server = mock(Server.class);
        Instant future = Instant.parse("2999-01-01T00:00:00Z");
        Instant past = Instant.parse("2000-01-01T00:00:00Z");
        ServerLoadSnapshot fresh = new ServerLoadSnapshot(new ServerLoad(4, true), past, future);
        ServerLoadSnapshot stale = new ServerLoadSnapshot(new ServerLoad(5, false), past, past.plusSeconds(1));
        when(fixture.echo().getServerById("server")).thenReturn(EchoFuture.completed(Optional.of(server)));
        when(server.getLoad()).thenReturn(EchoFuture.completed(Optional.empty()),
                EchoFuture.completed(Optional.of(fresh)), EchoFuture.completed(Optional.of(stale)));

        fixture.commands().serverLoad(fixture.context(), "server").join();
        fixture.commands().serverLoad(fixture.context(), "server").join();
        fixture.commands().serverLoad(fixture.context(), "server").join();

        assertThat(fixture.output()).contains("WARN: No server load has been published.",
                "participants: 4", "accepting queue assignments: true", "stale: false",
                "participants: 5", "accepting queue assignments: false", "stale: true");
    }

    @Test
    void resourceControlsCoverAllCommandsAcceptedRejectedAndConfirmation() {
        CommandTestSupport.Fixture fixture = fixture();
        controlResponse(fixture, true, ResourceControlRequest.Status.ACCEPTED, "accepted");

        fixture.commands().serverPing(fixture.context(), "server").join();
        fixture.commands().serverLoadRefresh(fixture.context(), "server").join();
        fixture.commands().serverDrain(fixture.context(), "server", 5, true).join();
        fixture.commands().serverActivate(fixture.context(), "server", true).join();
        fixture.commands().serverShutdown(fixture.context(), "server", false).join();
        fixture.commands().serverShutdown(fixture.context(), "server", true).join();
        fixture.commands().proxyDrain(fixture.context(), "proxy", 6, true).join();
        fixture.commands().proxyActivate(fixture.context(), "proxy", true).join();
        fixture.commands().proxyShutdown(fixture.context(), "proxy", true).join();

        controlResponse(fixture, false, ResourceControlRequest.Status.NOT_ALLOWED, "denied");
        fixture.commands().proxyPing(fixture.context(), "proxy").join();
        controlResponse(fixture, false, ResourceControlRequest.Status.FAILED, "redis password=secret");
        fixture.commands().proxyPing(fixture.context(), "proxy").join();

        ArgumentCaptor<ResourceControlRequest> requests = ArgumentCaptor.forClass(ResourceControlRequest.class);
        verify(fixture.messaging(), times(10)).request(
                argThat(topic -> topic.equals("server:server") || topic.equals("proxy:proxy")), requests.capture(),
                eq(ResourceControlRequest.Response.class), eq(Duration.ofSeconds(10)));
        assertThat(requests.getAllValues()).extracting(ResourceControlRequest::getAction)
                .contains(ResourceControlRequest.Action.PING, ResourceControlRequest.Action.REFRESH_LOAD,
                        ResourceControlRequest.Action.DRAIN, ResourceControlRequest.Action.ACTIVATE,
                        ResourceControlRequest.Action.SHUTDOWN);
        assertThat(requests.getAllValues()).extracting(ResourceControlRequest::getExpectedResourceType)
                .contains(EchoResourceType.SERVER, EchoResourceType.PROXY);
        assertThat(fixture.output()).contains("OK: accepted", "ERROR: Remote action was not allowed.",
                        "ERROR: Command failed. Reference:", "WARN: Run: echo server shutdown server --confirm")
                .doesNotContain("redis password=secret");
        verify(fixture.logger()).log(eq(Level.SEVERE), anyString(), any(Throwable.class));
        verify(fixture.logger(), atLeastOnce()).info(any(Supplier.class));
    }

    @Test
    void controlsReportInvalidDurationsSynchronousAndAsynchronousFailures() {
        CommandTestSupport.Fixture fixture = fixture();
        controlResponse(fixture, true, ResourceControlRequest.Status.ACCEPTED, "accepted");
        fixture.commands().serverDrain(fixture.context(), "server", 0, true).join();

        when(fixture.messaging().request(anyString(), any(ResourceControlRequest.class),
                eq(ResourceControlRequest.Response.class), any(Duration.class)))
                .thenReturn(echoFailed(new CompletionException(new IllegalStateException("control unavailable"))));
        fixture.commands().serverLoadRefresh(fixture.context(), "server").join();

        fixture.commands().serverPing(fixture.context(), "server").join();

        assertThat(fixture.output()).contains("ERROR: minutes must be positive",
                        "ERROR: Command failed. Reference:")
                .doesNotContain("control unavailable");
        verify(fixture.logger(), times(2)).log(eq(Level.SEVERE), anyString(), any(Throwable.class));
        verify(fixture.logger(), times(2)).info(any(Supplier.class));
    }

    @Test
    void userInfoResolvesUuidAndUsernameIncludingMissingFieldsAndUsers() {
        CommandTestSupport.Fixture fixture = fixture();
        UUID id = UUID.randomUUID();
        User complete = user(id, Optional.of("Alice"), Optional.of("proxy"), Optional.of("server"));
        User sparse = user(UUID.randomUUID(), Optional.empty(), Optional.empty(), Optional.empty());
        when(fixture.echo().getUserById(id)).thenReturn(EchoFuture.completed(Optional.of(complete)));
        when(fixture.echo().getUserByUsername("Sparse")).thenReturn(EchoFuture.completed(Optional.of(sparse)));
        when(fixture.echo().getUserById(new UUID(0, 0))).thenReturn(EchoFuture.completed(Optional.empty()));
        when(fixture.echo().getUserByUsername("Missing")).thenReturn(EchoFuture.completed(Optional.empty()));

        fixture.commands().userInfo(fixture.context(), id.toString()).join();
        fixture.commands().userInfo(fixture.context(), "Sparse").join();
        fixture.commands().userInfo(fixture.context(), new UUID(0, 0).toString()).join();
        fixture.commands().userInfo(fixture.context(), "Missing").join();

        assertThat(fixture.output()).contains("username: Alice", "proxy: proxy", "server: server",
                "username: unknown", "proxy: none", "server: none",
                "ERROR: User not found: " + new UUID(0, 0), "ERROR: User not found: Missing");
        verify(fixture.echo()).getUserById(id);
        verify(fixture.echo()).getUserByUsername("Sparse");
    }

    @Test
    void userSendCoversSuccessfulFailedAndExceptionalTransfers() {
        CommandTestSupport.Fixture fixture = fixture();
        User user = mock(User.class);
        ServerSwitchRequest.PlayerResponse success = new ServerSwitchRequest.PlayerResponse(
                true, ServerSwitchRequest.ServerSwitchRequestStatus.SUCCESS, null);
        ServerSwitchRequest.PlayerResponse failure = new ServerSwitchRequest.PlayerResponse(
                false, ServerSwitchRequest.ServerSwitchRequestStatus.TARGET_SERVER_UNAVAILABLE, null);
        when(fixture.echo().getUserByUsername("Alice")).thenReturn(EchoFuture.completed(Optional.of(user)));
        when(user.tryConnectToServer("one", Duration.ofSeconds(10))).thenReturn(EchoFuture.completed(success));
        when(user.tryConnectToServer("two", Duration.ofSeconds(10))).thenReturn(EchoFuture.completed(failure));
        when(user.tryConnectToServer("three", Duration.ofSeconds(10)))
                .thenReturn(echoFailed(new IllegalStateException("transfer broke")));

        fixture.commands().userSend(fixture.context(), "Alice", "one").join();
        fixture.commands().userSend(fixture.context(), "Alice", "two").join();
        fixture.commands().userSend(fixture.context(), "Alice", "three").join();

        assertThat(fixture.output()).contains("OK: User sent to one.",
                        "ERROR: User transfer failed: TARGET_SERVER_UNAVAILABLE.",
                        "ERROR: Command failed. Reference:")
                .doesNotContain("transfer broke");
        verify(fixture.logger(), times(3)).info(any(Supplier.class));
    }

    @Test
    void userDisconnectCoversRefusalAcceptedRejectedAndQuotedReason() {
        CommandTestSupport.Fixture fixture = fixture();
        User user = mock(User.class);
        UUID id = UUID.randomUUID();
        when(user.getId()).thenReturn(id);
        when(user.getCurrentProxyId()).thenReturn(EchoFuture.completed(Optional.of("proxy")));
        when(user.getSessionId()).thenReturn(EchoFuture.completed(Optional.of("session")));
        when(fixture.echo().getUserByUsername("Alice")).thenReturn(EchoFuture.completed(Optional.of(user)));

        fixture.commands().userDisconnect(fixture.context(), "Alice", "bad \"actor\" path", false).join();
        disconnectResponse(fixture, true, UserDisconnectRequest.Status.DISCONNECTED, "Disconnected");
        fixture.commands().userDisconnect(fixture.context(), "Alice", "maintenance", true).join();
        disconnectResponse(fixture, false, UserDisconnectRequest.Status.PLAYER_NOT_FOUND, "Gone");
        fixture.commands().userDisconnect(fixture.context(), "Alice", "maintenance", true).join();

        assertThat(fixture.output()).contains("WARN: Run: echo user disconnect Alice ", "--confirm",
                "OK: User disconnected.", "ERROR: Player was not found.");
        verify(fixture.messaging(), times(2)).request(eq("proxy:proxy"), any(UserDisconnectRequest.class),
                eq(UserDisconnectRequest.Response.class), any(Duration.class));
    }

    @Test
    void remoteResponseMatricesSanitizeEveryStatusAndInconsistentAcceptance() {
        CommandTestSupport.Fixture fixture = fixture();

        for (ResourceControlRequest.Status status : ResourceControlRequest.Status.values()) {
            controlResponse(fixture, false, status, "sensitive control detail");
            fixture.commands().serverPing(fixture.context(), "server").join();
        }
        controlResponse(fixture, true, ResourceControlRequest.Status.WRONG_TARGET, "inconsistent");
        fixture.commands().serverPing(fixture.context(), "server").join();

        User user = mock(User.class);
        when(user.getId()).thenReturn(UUID.randomUUID());
        when(user.getCurrentProxyId()).thenReturn(EchoFuture.completed(Optional.of("proxy")));
        when(user.getSessionId()).thenReturn(EchoFuture.completed(Optional.of("session")));
        when(fixture.echo().getUserByUsername("Alice")).thenReturn(EchoFuture.completed(Optional.of(user)));
        for (UserDisconnectRequest.Status status : UserDisconnectRequest.Status.values()) {
            disconnectResponse(fixture, false, status, "sensitive disconnect detail");
            fixture.commands().userDisconnect(fixture.context(), "Alice", "maintenance", true).join();
        }
        disconnectResponse(fixture, true, UserDisconnectRequest.Status.INVALID_REQUEST, "inconsistent");
        fixture.commands().userDisconnect(fixture.context(), "Alice", "maintenance", true).join();

        assertThat(fixture.output()).contains(
                        "Remote request was invalid.", "Remote request reached the wrong target.",
                        "Remote request timed out.", "Remote action was not allowed.",
                        "Remote action is not supported.", "Player was not found.",
                        "Remote disconnect request was invalid.",
                        "Remote disconnect request reached the wrong target.",
                        "Remote disconnect request timed out.", "Command failed. Reference:")
                .doesNotContain("sensitive control detail", "sensitive disconnect detail", "inconsistent");
        verify(fixture.logger(), atLeastOnce()).log(eq(Level.SEVERE), anyString(), any(Throwable.class));
    }

    @Test
    void suggestionsUseOneCappedPublicUserReadAndCoverFailure() {
        CommandTestSupport.Fixture fixture = fixture();
        Map<String, Long> servers = new LinkedHashMap<>();
        Map<String, Long> proxies = new LinkedHashMap<>();
        Map<UUID, Long> users = new LinkedHashMap<>();
        for (int index = 104; index >= 0; index--) {
            servers.put("server-%03d".formatted(index), (long) index);
            proxies.put("proxy-%03d".formatted(index), (long) index);
            users.put(new UUID(0, index), (long) index);
        }
        when(fixture.echo().getServers()).thenReturn(EchoFuture.completed(servers));
        when(fixture.echo().getProxies()).thenReturn(EchoFuture.completed(proxies));
        when(fixture.echo().getAllUsers()).thenReturn(EchoFuture.completed(users));

        assertThat(fixture.commands().serverSuggestions().join()).hasSize(100)
                .startsWith("server-000").endsWith("server-099");
        assertThat(fixture.commands().proxySuggestions().join()).hasSize(100)
                .startsWith("proxy-000").endsWith("proxy-099");
        assertThat(fixture.commands().userSuggestions().join()).hasSize(100)
                .startsWith(new UUID(0, 0).toString()).endsWith(new UUID(0, 99).toString());
        verify(fixture.echo()).getAllUsers();
        verify(fixture.echo(), never()).getUserById(any(UUID.class));

        when(fixture.echo().getServers()).thenReturn(echoFailed(new IllegalStateException("down")));
        when(fixture.echo().getProxies()).thenReturn(echoFailed(new IllegalStateException("down")));
        when(fixture.echo().getAllUsers()).thenReturn(echoFailed(new IllegalStateException("down")));
        assertThat(fixture.commands().serverSuggestions().join()).isEmpty();
        assertThat(fixture.commands().proxySuggestions().join()).isEmpty();
        assertThat(fixture.commands().userSuggestions().join()).isEmpty();
    }

    @Test
    void commandErrorsUnwrapBothWrapperTypesAndHandleBlankMessages() {
        CommandTestSupport.Fixture fixture = fixture();
        when(fixture.echo().getServers()).thenReturn(echoFailed(
                new CompletionException(new ExecutionException(new IllegalStateException("Redis unavailable")))));
        when(fixture.echo().getProxies()).thenReturn(EchoFuture.completed(Map.of()));
        when(fixture.echo().getAllUsers()).thenReturn(EchoFuture.completed(Map.of()));
        fixture.commands().status(fixture.context()).join();

        when(fixture.echo().getServers()).thenReturn(echoFailed(new IllegalStateException(" ")));
        fixture.commands().status(fixture.context()).join();

        when(fixture.echo().getServers()).thenReturn(echoFailed(new IllegalStateException((String) null)));
        fixture.commands().status(fixture.context()).join();

        when(fixture.echo().getServers()).thenReturn(echoFailed(new CompletionException((Throwable) null)));
        fixture.commands().status(fixture.context()).join();

        when(fixture.echo().getServers()).thenReturn(null);
        fixture.commands().status(fixture.context()).join();

        assertThat(fixture.output()).contains("ERROR: Command failed. Reference:")
                .doesNotContain("Redis unavailable", "IllegalStateException", "CompletionException", "Cannot invoke");
        verify(fixture.logger(), times(5)).log(eq(Level.SEVERE), anyString(), any(Throwable.class));
    }

    @Test
    void auditTargetsIdentifyDrainScopeDurationAndUserDestination() {
        CommandTestSupport.Fixture fixture = fixture();
        controlResponse(fixture, true, ResourceControlRequest.Status.ACCEPTED, "accepted");
        User user = mock(User.class);
        when(fixture.echo().getUserByUsername("Alice")).thenReturn(EchoFuture.completed(Optional.of(user)));
        when(user.tryConnectToServer("game-2", Duration.ofSeconds(10))).thenReturn(EchoFuture.completed(
                new ServerSwitchRequest.PlayerResponse(true,
                        ServerSwitchRequest.ServerSwitchRequestStatus.SUCCESS, null)));

        fixture.commands().serverDrain(fixture.context(), "game-1", 30, true).join();
        fixture.commands().proxyDrain(fixture.context(), "proxy-1", 15, true).join();
        fixture.commands().userSend(fixture.context(), "Alice", "game-2").join();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Supplier> entries = ArgumentCaptor.forClass(Supplier.class);
        verify(fixture.logger(), times(3)).info(entries.capture());
        assertThat(entries.getAllValues().stream().map(entry -> (String) entry.get()).toList()).contains(
                "echo_audit sender=audit_console_user action=server.drain target=server/game-1_duration=30m outcome=ACCEPTED",
                "echo_audit sender=audit_console_user action=proxy.drain target=proxy/proxy-1_duration=15m outcome=ACCEPTED",
                "echo_audit sender=audit_console_user action=user.send target=Alice->game-2 outcome=SUCCESS");
    }

    private static User user(UUID id, Optional<String> username, Optional<String> proxy, Optional<String> server) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getUsername()).thenReturn(EchoFuture.completed(username));
        when(user.getCurrentProxyId()).thenReturn(EchoFuture.completed(proxy));
        when(user.getCurrentServerId()).thenReturn(EchoFuture.completed(server));
        return user;
    }
}
