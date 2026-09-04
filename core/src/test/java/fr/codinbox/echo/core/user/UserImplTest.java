package fr.codinbox.echo.core.user;

import fr.codinbox.echo.api.Echo;
import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.cache.CacheProvider;
import fr.codinbox.echo.api.exception.resource.UnknownProxyException;
import fr.codinbox.echo.api.exception.user.UserHasNoProxyException;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.messaging.impl.ProxySwitchRequest;
import fr.codinbox.echo.api.messaging.impl.ServerSwitchRequest;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.core.messaging.MessageTargetBuilderImpl;
import fr.codinbox.echo.core.testutils.EchoTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@Tag("unit")
class UserImplTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String CURRENT_PROXY_ID = "current-proxy";
    private static final String PROXY_PROPERTY_KEY = "user:%s:property:current_proxy_id".formatted(USER_ID);

    private EchoClient client;
    private CacheProvider cache;
    private MessagingProvider messaging;
    private Proxy currentProxy;

    @BeforeEach
    void setUp() {
        EchoTestUtils.resetEchoClient();
        client = mock(EchoClient.class);
        cache = mock(CacheProvider.class);
        messaging = mock(MessagingProvider.class);
        currentProxy = mock(Proxy.class);
        when(client.getCacheProvider()).thenReturn(cache);
        when(client.getMessagingProvider()).thenReturn(messaging);
        when(client.getLocalTopic()).thenReturn("server:origin");
        when(client.newMessageTargetBuilder()).thenAnswer(ignored -> new MessageTargetBuilderImpl());
        Echo.initClient(client);
    }

    @Test
    void constants_shouldHaveCorrectValues() {
        assertThat(UserImpl.USERNAME_TO_ID_MAP).isEqualTo("users:username_to_id");
        assertThat(UserImpl.USER_MAP).isEqualTo("users:map");
        assertThat(UserImpl.USER_KEY_PREFIX).isEqualTo("user:%s");
    }

    @Test
    void getId_shouldReturnTheUuid() {
        UserImpl user = new UserImpl(USER_ID);

        assertThat(user.getId()).isEqualTo(USER_ID);
    }

    @Test
    void tryConnectToServer_defaultOverloadUsesTenSecondTimeout() {
        stubCurrentProxy();
        ServerSwitchRequest.PlayerResponse expected = stubResponse(Duration.ofSeconds(10));
        long startedAt = System.currentTimeMillis();

        assertThat(new UserImpl(USER_ID).tryConnectToServer("target-server").join()).isSameAs(expected);

        ArgumentCaptor<ServerSwitchRequest> request = ArgumentCaptor.forClass(ServerSwitchRequest.class);
        ArgumentCaptor<Duration> remaining = ArgumentCaptor.forClass(Duration.class);
        verify(messaging).request(eq("proxy:current-proxy"), request.capture(),
                eq(ServerSwitchRequest.Response.class), remaining.capture());
        assertThat(request.getValue().getServerId()).isEqualTo("target-server");
        assertThat(request.getValue().getUserUuids()).containsExactly(USER_ID);
        assertThat(request.getValue().getReplyTopic()).isEqualTo("server:origin");
        assertThat(request.getValue().getTransferDeadlineEpochMillis())
                .isBetween(startedAt + 10_000, System.currentTimeMillis() + 10_000);
        assertThat(remaining.getValue()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void tryConnectToServer_explicitTimeoutSetsDeadlineAndPassesRemainingTime() {
        Duration timeout = Duration.ofMillis(321);
        stubCurrentProxy();
        stubResponse(timeout);
        long startedAt = System.currentTimeMillis();

        new UserImpl(USER_ID).tryConnectToServer("target-server", timeout).join();

        ArgumentCaptor<ServerSwitchRequest> request = ArgumentCaptor.forClass(ServerSwitchRequest.class);
        ArgumentCaptor<Duration> remaining = ArgumentCaptor.forClass(Duration.class);
        verify(messaging).request(eq("proxy:current-proxy"), request.capture(),
                eq(ServerSwitchRequest.Response.class), remaining.capture());
        assertThat(request.getValue().getTransferDeadlineEpochMillis())
                .isBetween(startedAt + timeout.toMillis(), System.currentTimeMillis() + timeout.toMillis());
        assertThat(remaining.getValue()).isPositive().isLessThanOrEqualTo(timeout);
    }

    @Test
    void tryConnectToServer_rejectsTimeoutShorterThanOneMillisecond() {
        UserImpl user = new UserImpl(USER_ID);

        assertThatThrownBy(() -> user.tryConnectToServer("target-server", Duration.ofNanos(999_999)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("timeout must be at least 1ms");
        verifyNoInteractions(cache, messaging);
    }

    @Test
    void tryConnectToServer_pendingCurrentProxyLookupTimesOutWithoutMutatingSourceFuture() {
        EchoFuture<Optional<Proxy>> proxyLookup = new EchoFuture<>();
        when(cache.<String>getObject(PROXY_PROPERTY_KEY))
                .thenReturn(CompletableFuture.completedFuture(CURRENT_PROXY_ID));
        when(client.getProxyById(CURRENT_PROXY_ID)).thenReturn(proxyLookup);

        assertThatThrownBy(() -> new UserImpl(USER_ID)
                .tryConnectToServer("target-server", Duration.ofMillis(50)).join())
                .hasCauseInstanceOf(TimeoutException.class);

        assertThat(proxyLookup).isNotDone();
        verifyNoInteractions(messaging);
    }

    @Test
    void tryConnectToServer_whenDeadlineElapsesAfterProxyLookupDoesNotSendRequest() {
        stubCurrentProxy();
        long[] times = { 1_000, 1_000, 1_001 };
        AtomicInteger index = new AtomicInteger();

        assertThatThrownBy(() -> new UserImpl(USER_ID, () -> times[index.getAndIncrement()])
                .tryConnectToServer("target-server", Duration.ofMillis(1)).join())
                .hasCauseInstanceOf(TimeoutException.class);

        verifyNoInteractions(messaging);
    }

    @Test
    void tryConnectToServer_withoutCurrentProxyFails() {
        when(cache.<String>getObject(PROXY_PROPERTY_KEY)).thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> new UserImpl(USER_ID).tryConnectToServer("target-server").join())
                .hasCauseInstanceOf(UserHasNoProxyException.class);
    }

    @Test
    void tryConnectToServer_withUnknownCurrentProxyFails() {
        when(cache.<String>getObject(PROXY_PROPERTY_KEY))
                .thenReturn(CompletableFuture.completedFuture(CURRENT_PROXY_ID));
        when(client.getProxyById(CURRENT_PROXY_ID)).thenReturn(EchoFuture.completed(Optional.empty()));

        assertThatThrownBy(() -> new UserImpl(USER_ID).tryConnectToServer("target-server").join())
                .hasCauseInstanceOf(UnknownProxyException.class);
    }

    @Test
    void tryConnectToServer_propagatesCurrentProxyPropertyFailure() {
        IllegalStateException failure = new IllegalStateException("property lookup failed");
        when(cache.<String>getObject(PROXY_PROPERTY_KEY)).thenReturn(CompletableFuture.failedFuture(failure));

        assertThatThrownBy(() -> new UserImpl(USER_ID).tryConnectToServer("target-server").join())
                .hasRootCauseMessage("property lookup failed");
    }

    @Test
    void tryConnectToServer_propagatesProxyLookupFailure() {
        IllegalStateException failure = new IllegalStateException("proxy lookup failed");
        when(cache.<String>getObject(PROXY_PROPERTY_KEY))
                .thenReturn(CompletableFuture.completedFuture(CURRENT_PROXY_ID));
        when(client.getProxyById(CURRENT_PROXY_ID))
                .thenReturn(EchoFuture.of(CompletableFuture.failedFuture(failure)));

        assertThatThrownBy(() -> new UserImpl(USER_ID).tryConnectToServer("target-server").join())
                .hasRootCauseMessage("proxy lookup failed");
    }

    @Test
    void tryConnectToServer_propagatesMessagingFailure() {
        IllegalStateException failure = new IllegalStateException("messaging failed");
        stubCurrentProxy();
        when(messaging.request(eq("proxy:current-proxy"), any(ServerSwitchRequest.class),
                eq(ServerSwitchRequest.Response.class), any(Duration.class)))
                .thenReturn(EchoFuture.of(CompletableFuture.failedFuture(failure)));

        assertThatThrownBy(() -> new UserImpl(USER_ID).tryConnectToServer("target-server").join())
                .hasRootCauseMessage("messaging failed");
    }

    @Test
    void tryConnectToServer_whenResponseOmitsUserFailsProtocol() {
        stubCurrentProxy();
        when(messaging.request(eq("proxy:current-proxy"), any(ServerSwitchRequest.class),
                eq(ServerSwitchRequest.Response.class), any(Duration.class)))
                .thenReturn(EchoFuture.completed(new ServerSwitchRequest.Response(Map.of())));

        assertThatThrownBy(() -> new UserImpl(USER_ID).tryConnectToServer("target-server").join())
                .hasRootCauseMessage("Proxy response omitted player " + USER_ID);
    }

    @Test
    void tryConnectToServer_serverOverloadUsesServerId() {
        Server server = mock(Server.class);
        when(server.getId()).thenReturn("server-from-object");
        stubCurrentProxy();
        stubResponse(Duration.ofSeconds(10));

        new UserImpl(USER_ID).tryConnectToServer(server).join();

        verify(messaging).request(eq("proxy:current-proxy"),
                argThat(request -> ((ServerSwitchRequest) request).getServerId().equals("server-from-object")),
                eq(ServerSwitchRequest.Response.class), argThat(actual -> actual.isPositive()
                        && actual.compareTo(Duration.ofSeconds(10)) <= 0));
    }

    @Test
    void tryConnectToProxy_sendsRequestThroughCurrentProxy() {
        Proxy targetProxy = mock(Proxy.class);
        when(targetProxy.getId()).thenReturn("target-proxy");
        when(currentProxy.sendMessage(any(ProxySwitchRequest.class))).thenReturn(EchoFuture.completed(null));
        stubCurrentProxy();

        new UserImpl(USER_ID).tryConnectToProxy(targetProxy).join();

        verify(currentProxy).sendMessage(argThat(message ->
                message instanceof ProxySwitchRequest request
                        && request.getProxyId().equals("target-proxy")
                        && java.util.Arrays.equals(request.getUserUuids(), new UUID[] { USER_ID })));
        verify(targetProxy, never()).sendMessage(any());
    }

    @Test
    void cleanup_deletesUserProperties() {
        when(cache.getKeys("user:%s:property:*".formatted(USER_ID)))
                .thenReturn(CompletableFuture.completedFuture(Set.of()));

        new UserImpl(USER_ID).cleanup().join();

        verify(cache).getKeys("user:%s:property:*".formatted(USER_ID));
    }

    private void stubCurrentProxy() {
        when(cache.<String>getObject(PROXY_PROPERTY_KEY))
                .thenReturn(CompletableFuture.completedFuture(CURRENT_PROXY_ID));
        when(client.getProxyById(CURRENT_PROXY_ID)).thenReturn(EchoFuture.completed(Optional.of(currentProxy)));
        when(currentProxy.getId()).thenReturn(CURRENT_PROXY_ID);
    }

    private ServerSwitchRequest.PlayerResponse stubResponse(Duration timeout) {
        ServerSwitchRequest.PlayerResponse playerResponse = new ServerSwitchRequest.PlayerResponse(
                true, ServerSwitchRequest.ServerSwitchRequestStatus.SUCCESS, null);
        ServerSwitchRequest.Response response = new ServerSwitchRequest.Response(Map.of(USER_ID, playerResponse));
        when(messaging.request(eq("proxy:current-proxy"), any(ServerSwitchRequest.class),
                eq(ServerSwitchRequest.Response.class), argThat(actual -> actual.isPositive()
                        && actual.compareTo(timeout) <= 0))).thenReturn(EchoFuture.completed(response));
        return playerResponse;
    }
}
