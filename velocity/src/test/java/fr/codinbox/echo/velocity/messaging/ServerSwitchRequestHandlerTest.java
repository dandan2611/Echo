package fr.codinbox.echo.velocity.messaging;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerAvailability;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class ServerSwitchRequestHandlerTest {

    private static final String SERVER_ID = "lobby-1";
    private static final UUID FIRST_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private EchoClient client;
    private ProxyServer proxy;
    private ServerSwitchRequestHandler handler;

    @BeforeEach
    void setUp() {
        client = mock(EchoClient.class);
        proxy = mock(ProxyServer.class);
        handler = new ServerSwitchRequestHandler(mock(Logger.class), proxy);
    }

    @Test
    void absentTargetRepliesForEveryPlayer() {
        ServerSwitchRequest request = request(FIRST_PLAYER, SECOND_PLAYER);
        when(client.getServerById(SERVER_ID)).thenReturn(EchoFuture.completed(Optional.empty()));

        receive(request);

        assertAllResponses(request, ServerSwitchRequest.ServerSwitchRequestStatus.TARGET_SERVER_NOT_FOUND,
                FIRST_PLAYER, SECOND_PLAYER);
    }

    @Test
    void expiredTransferDoesNotResolveOrConnectPlayers() {
        ServerSwitchRequest request = request(FIRST_PLAYER, SECOND_PLAYER);
        when(request.getTransferDeadlineEpochMillis()).thenReturn(System.currentTimeMillis() - 1);

        receive(request);

        assertAllResponses(request, ServerSwitchRequest.ServerSwitchRequestStatus.TIMED_OUT,
                FIRST_PLAYER, SECOND_PLAYER);
        verify(client, never()).getServerById(any());
        verify(proxy, never()).getPlayer(any(UUID.class));
    }

    @Test
    void drainingTargetRepliesForEveryPlayer() {
        ServerSwitchRequest request = request(FIRST_PLAYER, SECOND_PLAYER);
        target(ServerAvailability.DRAINING);

        receive(request);

        assertAllResponses(request, ServerSwitchRequest.ServerSwitchRequestStatus.TARGET_SERVER_UNAVAILABLE,
                FIRST_PLAYER, SECOND_PLAYER);
    }

    @Test
    void unregisteredTargetRepliesForEveryPlayer() {
        ServerSwitchRequest request = request(FIRST_PLAYER, SECOND_PLAYER);
        target(ServerAvailability.ACTIVE);
        when(proxy.getServer(SERVER_ID)).thenReturn(Optional.empty());

        receive(request);

        assertAllResponses(request, ServerSwitchRequest.ServerSwitchRequestStatus.TARGET_SERVER_NOT_REGISTERED,
                FIRST_PLAYER, SECOND_PLAYER);
    }

    @Test
    void offlinePlayersReceiveIndividualNotConnectedResults() {
        ServerSwitchRequest request = request(FIRST_PLAYER, SECOND_PLAYER);
        activeRegisteredTarget();
        when(proxy.getPlayer(FIRST_PLAYER)).thenReturn(Optional.empty());
        when(proxy.getPlayer(SECOND_PLAYER)).thenReturn(Optional.empty());

        receive(request);

        assertAllResponses(request, ServerSwitchRequest.ServerSwitchRequestStatus.PLAYER_NOT_CONNECTED,
                FIRST_PLAYER, SECOND_PLAYER);
    }

    @ParameterizedTest(name = "{0} maps to {1} with successful={2}")
    @MethodSource("connectionStatuses")
    void mapsEveryConnectionStatus(ConnectionRequestBuilder.Status velocityStatus,
                                   ServerSwitchRequest.ServerSwitchRequestStatus responseStatus,
                                   boolean successful) {
        ServerSwitchRequest request = request(FIRST_PLAYER);
        RegisteredServer registeredServer = activeRegisteredTarget();
        ConnectionRequestBuilder.Result result = result(velocityStatus, Optional.empty());
        connect(FIRST_PLAYER, registeredServer, CompletableFuture.completedFuture(result));

        receive(request);

        ServerSwitchRequest.PlayerResponse response = captureResponses(request).get(FIRST_PLAYER);
        assertThat(response.getStatus()).isEqualTo(responseStatus);
        assertThat(response.isSuccessful()).isEqualTo(successful);
        assertThat(response.getSerializedReason()).isNull();
    }

    @Test
    void serializesConnectionReasonWhenPresent() {
        ServerSwitchRequest request = request(FIRST_PLAYER);
        RegisteredServer registeredServer = activeRegisteredTarget();
        ConnectionRequestBuilder.Result result = result(
                ConnectionRequestBuilder.Status.SERVER_DISCONNECTED,
                Optional.of(Component.text("Maintenance")));
        connect(FIRST_PLAYER, registeredServer, CompletableFuture.completedFuture(result));

        receive(request);

        ServerSwitchRequest.PlayerResponse response = captureResponses(request).get(FIRST_PLAYER);
        assertThat(response.isSuccessful()).isFalse();
        assertThat(response.getStatus())
                .isEqualTo(ServerSwitchRequest.ServerSwitchRequestStatus.SERVER_DISCONNECTED);
        assertThat(response.getSerializedReason()).isEqualTo("\"Maintenance\"");
    }

    @Test
    void exceptionalConnectionIsIsolatedFromOtherPlayers() {
        ServerSwitchRequest request = request(FIRST_PLAYER, SECOND_PLAYER);
        RegisteredServer registeredServer = activeRegisteredTarget();
        CompletableFuture<ConnectionRequestBuilder.Result> failedConnection = new CompletableFuture<>();
        connect(FIRST_PLAYER, registeredServer, failedConnection);
        connect(SECOND_PLAYER, registeredServer, CompletableFuture.completedFuture(
                result(ConnectionRequestBuilder.Status.SUCCESS, Optional.empty())));

        receive(request);
        verify(request, never()).reply(any(ServerSwitchRequest.Response.class));
        failedConnection.completeExceptionally(new IllegalStateException("Connection failed"));

        Map<UUID, ServerSwitchRequest.PlayerResponse> responses = captureResponses(request);
        assertResponse(responses.get(FIRST_PLAYER), false,
                ServerSwitchRequest.ServerSwitchRequestStatus.INTERNAL_ERROR, null);
        assertResponse(responses.get(SECOND_PLAYER), true,
                ServerSwitchRequest.ServerSwitchRequestStatus.SUCCESS, null);
    }

    @Test
    void connectionThatNeverCompletesTimesOutWithoutBlockingOtherPlayerOutcomes() {
        ServerSwitchRequest request = request(FIRST_PLAYER, SECOND_PLAYER);
        when(request.getTransferDeadlineEpochMillis()).thenReturn(System.currentTimeMillis() + 200);
        RegisteredServer registeredServer = activeRegisteredTarget();
        CompletableFuture<ConnectionRequestBuilder.Result> pendingConnection = new CompletableFuture<>();
        connect(FIRST_PLAYER, registeredServer, pendingConnection);
        connect(SECOND_PLAYER, registeredServer, CompletableFuture.completedFuture(
                result(ConnectionRequestBuilder.Status.SUCCESS, Optional.empty())));

        receive(request);

        ArgumentCaptor<ServerSwitchRequest.Response> response =
                ArgumentCaptor.forClass(ServerSwitchRequest.Response.class);
        verify(request, timeout(1_000)).reply(response.capture());
        Map<UUID, ServerSwitchRequest.PlayerResponse> responses = response.getValue().getResponses();
        assertThat(responses).containsOnlyKeys(FIRST_PLAYER, SECOND_PLAYER);
        assertResponse(responses.get(FIRST_PLAYER), false,
                ServerSwitchRequest.ServerSwitchRequestStatus.TIMED_OUT, null);
        assertResponse(responses.get(SECOND_PLAYER), true,
                ServerSwitchRequest.ServerSwitchRequestStatus.SUCCESS, null);
        assertThat(pendingConnection).isNotDone();
    }

    @Test
    void synchronousConnectionFailureIsIsolatedFromOtherPlayers() {
        ServerSwitchRequest request = request(FIRST_PLAYER, SECOND_PLAYER);
        RegisteredServer registeredServer = activeRegisteredTarget();
        Player failingPlayer = mock(Player.class);
        when(proxy.getPlayer(FIRST_PLAYER)).thenReturn(Optional.of(failingPlayer));
        when(failingPlayer.createConnectionRequest(registeredServer))
                .thenThrow(new IllegalStateException("Connection setup failed"));
        connect(SECOND_PLAYER, registeredServer, CompletableFuture.completedFuture(
                result(ConnectionRequestBuilder.Status.SUCCESS, Optional.empty())));

        receive(request);

        Map<UUID, ServerSwitchRequest.PlayerResponse> responses = captureResponses(request);
        assertResponse(responses.get(FIRST_PLAYER), false,
                ServerSwitchRequest.ServerSwitchRequestStatus.INTERNAL_ERROR, null);
        assertResponse(responses.get(SECOND_PLAYER), true,
                ServerSwitchRequest.ServerSwitchRequestStatus.SUCCESS, null);
    }

    @Test
    void serverLookupFailureRepliesOnceForEveryPlayer() {
        ServerSwitchRequest request = request(FIRST_PLAYER, SECOND_PLAYER);
        EchoFuture<Optional<Server>> failedLookup = new EchoFuture<>();
        failedLookup.completeExceptionally(new IllegalStateException("Redis unavailable"));
        when(client.getServerById(SERVER_ID)).thenReturn(failedLookup);

        receive(request);

        assertAllResponses(request, ServerSwitchRequest.ServerSwitchRequestStatus.INTERNAL_ERROR,
                FIRST_PLAYER, SECOND_PLAYER);
    }

    @Test
    void availabilityLookupFailureRepliesOnceForEveryPlayer() {
        ServerSwitchRequest request = request(FIRST_PLAYER, SECOND_PLAYER);
        Server server = mock(Server.class);
        EchoFuture<ServerAvailability> failedLookup = new EchoFuture<>();
        failedLookup.completeExceptionally(new IllegalStateException("Redis unavailable"));
        when(client.getServerById(SERVER_ID)).thenReturn(EchoFuture.completed(Optional.of(server)));
        when(server.getAvailability()).thenReturn(failedLookup);

        receive(request);

        assertAllResponses(request, ServerSwitchRequest.ServerSwitchRequestStatus.INTERNAL_ERROR,
                FIRST_PLAYER, SECOND_PLAYER);
    }

    private static Stream<Arguments> connectionStatuses() {
        return Stream.of(
                Arguments.of(ConnectionRequestBuilder.Status.SUCCESS,
                        ServerSwitchRequest.ServerSwitchRequestStatus.SUCCESS, true),
                Arguments.of(ConnectionRequestBuilder.Status.ALREADY_CONNECTED,
                        ServerSwitchRequest.ServerSwitchRequestStatus.ALREADY_CONNECTED, true),
                Arguments.of(ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS,
                        ServerSwitchRequest.ServerSwitchRequestStatus.CONNECTION_IN_PROGRESS, false),
                Arguments.of(ConnectionRequestBuilder.Status.CONNECTION_CANCELLED,
                        ServerSwitchRequest.ServerSwitchRequestStatus.CONNECTION_CANCELLED, false),
                Arguments.of(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED,
                        ServerSwitchRequest.ServerSwitchRequestStatus.SERVER_DISCONNECTED, false)
        );
    }

    private ServerSwitchRequest request(UUID... players) {
        ServerSwitchRequest request = mock(ServerSwitchRequest.class);
        when(request.getServerId()).thenReturn(SERVER_ID);
        when(request.getUserUuids()).thenReturn(players);
        return request;
    }

    private Server target(ServerAvailability availability) {
        Server server = mock(Server.class);
        when(client.getServerById(SERVER_ID)).thenReturn(EchoFuture.completed(Optional.of(server)));
        when(server.getId()).thenReturn(SERVER_ID);
        when(server.getAvailability()).thenReturn(EchoFuture.completed(availability));
        return server;
    }

    private RegisteredServer activeRegisteredTarget() {
        target(ServerAvailability.ACTIVE);
        RegisteredServer registeredServer = mock(RegisteredServer.class);
        when(proxy.getServer(SERVER_ID)).thenReturn(Optional.of(registeredServer));
        return registeredServer;
    }

    private void connect(UUID playerId, RegisteredServer registeredServer,
                         CompletableFuture<ConnectionRequestBuilder.Result> result) {
        Player player = mock(Player.class);
        ConnectionRequestBuilder connectionRequest = mock(ConnectionRequestBuilder.class);
        when(proxy.getPlayer(playerId)).thenReturn(Optional.of(player));
        when(player.createConnectionRequest(registeredServer)).thenReturn(connectionRequest);
        when(connectionRequest.connect()).thenReturn(result);
    }

    private static ConnectionRequestBuilder.Result result(ConnectionRequestBuilder.Status status,
                                                          Optional<Component> reason) {
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(result.getStatus()).thenReturn(status);
        when(result.getReasonComponent()).thenReturn(reason);
        return result;
    }

    private void receive(ServerSwitchRequest request) {
        try (MockedStatic<Echo> echo = mockStatic(Echo.class)) {
            echo.when(Echo::getClient).thenReturn(client);
            handler.onReceive(request);
        }
    }

    private static Map<UUID, ServerSwitchRequest.PlayerResponse> captureResponses(ServerSwitchRequest request) {
        ArgumentCaptor<ServerSwitchRequest.Response> response =
                ArgumentCaptor.forClass(ServerSwitchRequest.Response.class);
        verify(request).reply(response.capture());
        return response.getValue().getResponses();
    }

    private static void assertAllResponses(ServerSwitchRequest request,
                                           ServerSwitchRequest.ServerSwitchRequestStatus status,
                                           UUID... players) {
        Map<UUID, ServerSwitchRequest.PlayerResponse> responses = captureResponses(request);
        assertThat(responses).containsOnlyKeys(players);
        assertThat(responses.values()).allSatisfy(response -> assertResponse(response, false, status, null));
    }

    private static void assertResponse(ServerSwitchRequest.PlayerResponse response, boolean successful,
                                       ServerSwitchRequest.ServerSwitchRequestStatus status, String reason) {
        assertThat(response.isSuccessful()).isEqualTo(successful);
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getSerializedReason()).isEqualTo(reason);
    }
}
