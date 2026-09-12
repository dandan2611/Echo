package fr.codinbox.echo.core;

import fr.codinbox.echo.api.EchoConfig;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.cache.CacheMap;
import fr.codinbox.echo.api.cache.CacheProvider;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.core.testutils.EchoTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Tag("unit")
class EchoClientImplTest {

    @BeforeEach
    void resetEcho() {
        EchoTestUtils.resetEchoClient();
    }

    @Test
    void createLocalResource_waitsForInitialPropertiesBeforeHeartbeat() throws Exception {
        CacheProvider cache = mock(CacheProvider.class);
        CompletableFuture<Void> propertyWrite = new CompletableFuture<>();
        CountDownLatch propertyStarted = new CountDownLatch(1);
        stubProviderLifecycle(cache);
        when(cache.setObject(anyString(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key.equals("server:test-server:property:server_type")) {
                propertyStarted.countDown();
                return propertyWrite;
            }
            return CompletableFuture.completedFuture(null);
        });
        when(cache.setObject(anyString(), any(), any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        EchoClientImpl client = createClient(cache, Map.of(new PropertyKey<>("server_type"), "lobby"));
        CompletableFuture<Void> creation = CompletableFuture.runAsync(
                () -> client.createLocalResource(new Address("127.0.0.1", 25565)));

        assertThat(propertyStarted.await(1, TimeUnit.SECONDS)).isTrue();
        verify(cache, never()).setObject(eq("heartbeat:server:test-server"), any());
        verify(cache, never()).setObject(eq("heartbeat:server:test-server"), any(), any(Duration.class));

        propertyWrite.complete(null);
        creation.join();

        verify(cache).setObject(eq("heartbeat:server:test-server"), anyLong(), eq(Duration.ofSeconds(30)));
        verify(cache, never()).setObject(eq("heartbeat:server:test-server"), any());
        verify(cache, never()).expireObject(eq("heartbeat:server:test-server"), any(Duration.class));
    }

    @Test
    void createLocalResource_whenInitialPropertyFails_doesNotCreateHeartbeat() {
        CacheProvider cache = mock(CacheProvider.class);
        stubProviderLifecycle(cache);
        when(cache.setObject(anyString(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key.equals("server:test-server:property:server_type"))
                return CompletableFuture.failedFuture(new IllegalStateException("Redis unavailable"));
            return CompletableFuture.completedFuture(null);
        });

        EchoClientImpl client = createClient(cache, Map.of(new PropertyKey<>("server_type"), "lobby"));

        assertThatThrownBy(() -> client.createLocalResource(new Address("127.0.0.1", 25565)))
                .hasRootCauseMessage("Redis unavailable");
        verify(cache, never()).setObject(eq("heartbeat:server:test-server"), any());
        verify(cache, never()).setObject(eq("heartbeat:server:test-server"), any(), any(Duration.class));
    }

    @Test
    void emitHeartbeat_writesValueAndExpirationTogether() throws Exception {
        CacheProvider cache = mock(CacheProvider.class);
        stubProviderLifecycle(cache);
        when(cache.setObject(anyString(), any(), any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        EchoClientImpl client = createClient(cache, Map.of());
        var emitHeartbeat = EchoClientImpl.class.getDeclaredMethod("emitHeartbeat");
        emitHeartbeat.setAccessible(true);

        emitHeartbeat.invoke(client);

        verify(cache).setObject(eq("heartbeat:server:test-server"), anyLong(), eq(Duration.ofSeconds(30)));
        verify(cache, never()).setObject(eq("heartbeat:server:test-server"), any());
        verify(cache, never()).expireObject(anyString(), any(Duration.class));
    }

    @Test
    void getUserById_whenUsernameLookupFails_propagatesFailure() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CacheProvider cache = mock(CacheProvider.class);
        stubProviderLifecycle(cache);
        when(cache.<String>getObject("user:%s:property:username".formatted(userId)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("username lookup failed")));
        EchoClientImpl client = createClient(cache, Map.of());

        assertThatThrownBy(() -> client.getUserById(userId).join())
                .hasRootCauseMessage("username lookup failed");
    }

    @Test
    void getServerPlacement_returnsConfiguredPlacement() {
        CacheProvider cache = mock(CacheProvider.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        stubProviderLifecycle(cache);

        EchoClientImpl client = createClient(cache, Map.of(), placement);

        assertThat(client.getServerPlacement()).isSameAs(placement);
    }

    @Test
    void getServerPlacement_whenNotConfigured_rejectsAccess() {
        CacheProvider cache = mock(CacheProvider.class);
        stubProviderLifecycle(cache);
        EchoClientImpl client = createClient(cache, Map.of());

        assertThatThrownBy(client::getServerPlacement)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placement");
    }

    @Test
    void createUserPersistsTheSessionWithProxyState() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        CacheProvider cache = mock(CacheProvider.class);
        CacheMap<String, String> usernames = mock(CacheMap.class);
        CacheMap<String, Long> users = mock(CacheMap.class);
        CacheMap<String, Long> proxyUsers = mock(CacheMap.class);
        stubProviderLifecycle(cache);
        stubLifecycleLock(cache);
        when(cache.setObject(anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(cache.<String, String>getMap("users:username_to_id")).thenReturn(usernames);
        when(cache.<String, Long>getMap("users:map")).thenReturn(users);
        when(cache.<String, Long>getMap("proxy:proxy-1:users")).thenReturn(proxyUsers);
        when(usernames.putAsync("alice", userId.toString())).thenReturn(CompletableFuture.completedFuture(null));
        when(users.fastPutAsync(eq(userId.toString()), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(proxyUsers.fastPutAsync(eq(userId.toString()), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(true));
        EchoClientImpl client = createClient(cache, Map.of());

        client.createUser(userId, "Alice", "proxy-1", "session-1").join();

        verify(cache).setObject("user:%s:property:current_proxy_id".formatted(userId), "proxy-1");
        verify(cache).setObject("user:%s:property:session_id".formatted(userId), "session-1");
        verify(proxyUsers).fastPutAsync(eq(userId.toString()), anyLong());
    }

    @Test
    void staleSessionDoesNotDestroyReplacementState() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        CacheProvider cache = mock(CacheProvider.class);
        stubProviderLifecycle(cache);
        stubLifecycleLock(cache);
        EchoClientImpl client = createClient(cache, Map.of());
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getSessionId()).thenReturn(EchoFuture.completed(Optional.of("new-session")));

        client.destroyUser(user, "old-session").join();

        verify(user, never()).cleanup();
        verify(cache, never()).getMap(anyString());
    }

    @Test
    void matchingSessionRemovesEveryIndexBeforePropertyCleanup() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000012");
        CacheProvider cache = mock(CacheProvider.class);
        CacheMap<String, String> usernames = mock(CacheMap.class);
        CacheMap<String, Long> users = mock(CacheMap.class);
        CacheMap<String, Long> serverUsers = mock(CacheMap.class);
        CacheMap<String, Long> proxyUsers = mock(CacheMap.class);
        CompletableFuture<String> usernameRemoval = new CompletableFuture<>();
        stubProviderLifecycle(cache);
        stubLifecycleLock(cache);
        when(cache.<String, String>getMap("users:username_to_id")).thenReturn(usernames);
        when(cache.<String, Long>getMap("users:map")).thenReturn(users);
        when(cache.<String, Long>getMap("server:server-1:users")).thenReturn(serverUsers);
        when(cache.<String, Long>getMap("proxy:proxy-1:users")).thenReturn(proxyUsers);
        when(usernames.removeAsync("alice")).thenReturn(usernameRemoval);
        when(users.fastRemoveAsync(userId.toString())).thenReturn(CompletableFuture.completedFuture(1L));
        when(serverUsers.fastRemoveAsync(userId.toString())).thenReturn(CompletableFuture.completedFuture(1L));
        when(proxyUsers.fastRemoveAsync(userId.toString())).thenReturn(CompletableFuture.completedFuture(1L));
        EchoClientImpl client = createClient(cache, Map.of());
        User user = user(userId, "session-1");
        when(user.cleanup()).thenReturn(EchoFuture.completed(null));

        EchoFuture<Void> destruction = client.destroyUser(user, "session-1");

        verify(user, never()).cleanup();
        usernameRemoval.complete(userId.toString());
        destruction.join();

        verify(user).cleanup();
        verify(serverUsers).fastRemoveAsync(userId.toString());
        verify(proxyUsers).fastRemoveAsync(userId.toString());
        verify(users).fastRemoveAsync(userId.toString());
    }

    @Test
    void indexCleanupFailurePropagatesAndPreservesProperties() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000013");
        CacheProvider cache = mock(CacheProvider.class);
        CacheMap<String, String> usernames = mock(CacheMap.class);
        CacheMap<String, Long> users = mock(CacheMap.class);
        CacheMap<String, Long> serverUsers = mock(CacheMap.class);
        CacheMap<String, Long> proxyUsers = mock(CacheMap.class);
        stubProviderLifecycle(cache);
        stubLifecycleLock(cache);
        when(cache.<String, String>getMap("users:username_to_id")).thenReturn(usernames);
        when(cache.<String, Long>getMap("users:map")).thenReturn(users);
        when(cache.<String, Long>getMap("server:server-1:users")).thenReturn(serverUsers);
        when(cache.<String, Long>getMap("proxy:proxy-1:users")).thenReturn(proxyUsers);
        when(usernames.removeAsync("alice"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("index unavailable")));
        when(users.fastRemoveAsync(userId.toString())).thenReturn(CompletableFuture.completedFuture(1L));
        when(serverUsers.fastRemoveAsync(userId.toString())).thenReturn(CompletableFuture.completedFuture(1L));
        when(proxyUsers.fastRemoveAsync(userId.toString())).thenReturn(CompletableFuture.completedFuture(1L));
        EchoClientImpl client = createClient(cache, Map.of());
        User user = user(userId, "session-1");

        assertThatThrownBy(() -> client.destroyUser(user, "session-1").join())
                .hasRootCauseMessage("index unavailable");
        verify(user, never()).cleanup();
    }

    private EchoClientImpl createClient(CacheProvider cache, Map<PropertyKey<String>, String> properties) {
        return createClient(cache, properties, null);
    }

    private EchoClientImpl createClient(CacheProvider cache, Map<PropertyKey<String>, String> properties,
                                        ServerPlacement placement) {
        MessagingProvider messaging = mock(MessagingProvider.class);
        when(messaging.init()).thenReturn(CompletableFuture.completedFuture(null));
        EchoConfig.Builder builder = EchoConfig.builder()
                .cacheProviderFactory(() -> cache)
                .messagingProviderFactory(() -> messaging)
                .resourceType(EchoResourceType.SERVER)
                .resourceId("test-server")
                .initialProperties(properties);
        if (placement != null)
            builder.serverPlacement(placement);
        return new EchoClientImpl(builder.build());
    }

    private void stubProviderLifecycle(CacheProvider cache) {
        when(cache.init()).thenReturn(CompletableFuture.completedFuture(null));
    }

    private void stubLifecycleLock(CacheProvider cache) {
        when(cache.withLock(anyString(), anyLong(), anyLong(), eq(TimeUnit.SECONDS), any()))
                .thenAnswer(invocation -> {
                    Supplier<CompletableFuture<Void>> action = invocation.getArgument(4);
                    return action.get().thenApply(ignored -> true);
                });
    }

    private User user(UUID userId, String sessionId) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getSessionId()).thenReturn(EchoFuture.completed(Optional.of(sessionId)));
        when(user.getUsername()).thenReturn(EchoFuture.completed(Optional.of("Alice")));
        when(user.getCurrentServerId()).thenReturn(EchoFuture.completed(Optional.of("server-1")));
        when(user.getCurrentProxyId()).thenReturn(EchoFuture.completed(Optional.of("proxy-1")));
        return user;
    }
}
