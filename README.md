# Echo

A distributed messaging and resource management library for Minecraft server networks, built on Redis.

Echo provides a unified API to track players, servers, and proxies across your entire infrastructure, with built-in pub/sub messaging, property storage, and server switching capabilities.

## Features

- **Network-wide resource tracking** - Query and manage users, servers, and proxies from any node
- **Distributed messaging** - Publish/subscribe messaging with typed handlers and request/response patterns
- **Property storage** - Attach arbitrary key-value properties with optional TTL to any resource
- **Server switching** - Transfer players between servers and proxies with status feedback
- **Graceful draining** - Stop new joins while keeping existing players and heartbeats alive
- **On-demand servers** - Acquire and terminate disposable servers without exposing the orchestrator
- **Atomic placement and queues** - Reserve whole groups and coordinate recoverable Redis-backed handoffs
- **Async-first API** - Core operations use `EchoFuture`; orchestration modules use `CompletableFuture`
- **Platform integrations** - Ready-to-use plugins for [Paper](https://papermc.io/) and [Velocity](https://velocitypowered.com/)

## Requirements

- Java 21+ for the libraries and Velocity; Java 25+ and Paper 26.2 for the Paper plugin
- Redis server
- [Connector](https://github.com/dandan2611/Connector) library for Redis connection management

## Modules

| Module | Description |
|--------|-------------|
| `api` | Public API interfaces and contracts |
| `core` | Core implementation backed by Redis (Redisson) |
| `ondemand` | Protocol-agnostic server acquisition contract |
| `agones` | Agones allocation and game-server lifecycle adapter |
| `queue` | Redis-backed group queues, placement, and recoverable player handoff |
| `commands` | Shared Cloud Annotations administration commands for servers and proxies |
| `paper` | Paper server plugin - auto-registers players and servers |
| `velocity` | Velocity proxy plugin - handles server switching and player routing |

### API map

| Area | Public entry points |
|------|---------------------|
| Client/config | `EchoClient` availability, load, placement, and session methods; `EchoConfig` initial properties, load provider, placement, and `Supplier` factories |
| Load | `ServerLoad`, `ServerLoadSnapshot`, `ServerLoadProvider`, `ServerLoadManager` |
| Placement | `ServerPlacement` requests, reservations, policies, monitoring, and explanations |
| Messaging/admin | `MessagingProvider.request`, `MessageTarget.builder`, `RemoteAdministration`, `ResourceControlRequest`, `UserDisconnectRequest`, availability notifications, bounded switch statuses |
| On demand | `OnDemandServers`, `ServerRequest`, `ServerHandle`, `OnDemandAdministration` allocation/reconciliation records |
| Agones | `AgonesOnDemandServers`, `AgonesGameServerLifecycle` |
| Queues | `QueueId`, `QueueDefinition`, `QueueOptions`, `QueueRequest`, `QueueRequestStatus`, `QueuePlacementAssignment`, `QueuePlacementPreparer`, `QueueService`, `QueueAdministration`, `RedisQueue` |
| Commands | `EchoCommands`, `CommandAudience`, `CommandFormatter` |
| Paper | `EchoPaper` drain/load/activation methods and `ServerDrainEvent` |
| Velocity | `EchoPlugin` drain/activation/shutdown and session-aware disconnect methods |

## Installation

### Maven

```xml
<repository>
    <id>codinbox-releases</id>
    <url>https://nexus.codinbox.fr/repository/maven-public/</url>
</repository>
```

```xml
<dependency>
    <groupId>fr.codinbox.echo</groupId>
    <artifactId>api</artifactId>
    <version>7.1.0</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven("https://nexus.codinbox.fr/repository/maven-public/")
}

dependencies {
    implementation("fr.codinbox.echo:api:7.1.0")
}
```

Replace `api` with the artifact needed by the integration: `core`, `ondemand`, `agones`, `queue`,
or `commands`. Use the `paper` and `velocity` shadow JARs as plugins rather than application
dependencies. The platform JARs include the shared commands, queue protocol, and in-pod Agones
lifecycle, but intentionally exclude the Fabric8 allocation client; allocation controllers must
depend on `fr.codinbox.echo:agones:7.1.0`.

The Maven `paper` and `velocity` main artifacts are complete runtime plugin JARs (not API-only
or thin JARs). For automated installation, download `paper/7.1.0/paper-7.1.0.jar` or
`velocity/7.1.0/velocity-7.1.0.jar` beneath
`https://nexus.codinbox.fr/repository/maven-public/fr/codinbox/echo/`.
Each module also publishes its `-sources.jar`. Use the public group URL for anonymous reads;
the hosted `maven-releases` endpoint requires authentication.

## Configuration

Echo reads its configuration from environment variables:

| Variable | Description | Example |
|----------|-------------|---------|
| `ECHO_RESOURCE_TYPE` | The type of this node | `SERVER` or `PROXY` |
| `ECHO_RESOURCE_ID` | Unique identifier for this node | `lobby-1`, `proxy-eu` |
| `ECHO_RESOURCE_ADDRESS` | The address of this node | `127.0.0.1:25565` |
| `ECHO_AGONES_ENABLED` | Enable the optional Agones lifecycle | `true` (default: `false`) |
| `ECHO_AGONES_LONG_LIVED` | Self-allocate and observe rollout drain requests | `true` for lobbies, otherwise `false` |
| `ECHO_AGONES_DRAIN_ANNOTATION` | Annotation that requests a graceful drain | `echo.codinbox.fr/draining` |
| `ECHO_RESOURCE_PROPERTY_<key>` | String-valued initial property, applied before discovery | `ECHO_RESOURCE_PROPERTY_region=eu-west` |

A Redis connection named `ECHO` must be registered through the Connector library. Initial-property
suffixes are exact and case-sensitive; empty suffixes and the reserved keys `creation_time`,
`availability`, and `load` are rejected. Paper converts `placement_capacity` and
`placement_hard_capacity` into integer properties, defaulting to 100 and 120 respectively,
and sets `max-players` to the hard capacity. Set
`ECHO_RESOURCE_PROPERTY_placement_capacity=80` for a destination with 80 public seats.

Paper and Velocity enable their Agones lifecycle only when `ECHO_AGONES_ENABLED=true`. In that case,
the Agones sidecar must inject `AGONES_SDK_HTTP_PORT`. Without the flag, Echo runs without Agones.

Programmatic configuration uses `EchoConfig.builder()`. Provider factories are standard
`Supplier<? extends CacheProvider>` and `Supplier<? extends MessagingProvider>` values. Servers can
also set `initialProperties`, a default `serverLoadProvider`, and a `serverPlacement`
implementation; proxies cannot configure a load provider.

### Server availability

Servers are `ACTIVE` by default. Marking a server `DRAINING` keeps it in Echo for heartbeat and
player tracking, but immediately removes it from every Velocity proxy as a destination:

```java
Echo.getClient().setLocalServerAvailability(ServerAvailability.DRAINING);
```

When Agones requests a long-lived server drain during a Fleet rollout, EchoPaper first persists
`DRAINING`, then fires `ServerDrainEvent` on the Paper main thread. Game plugins can use its
absolute deadline to stop starting games, warn players, and finish or migrate current sessions:

```java
@EventHandler
public void onServerDrain(ServerDrainEvent event) {
    gameManager.stopAcceptingGames(event.getDeadline());
}
```

EchoPaper shuts the GameServer down as soon as it becomes empty, or after 30 minutes at the latest.

### Server load

Servers publish a `ServerLoadSnapshot` containing the participant count, whether queue assignments
are accepted, and the sample validity window. Paper provides player-count load automatically.
Custom game plugins can temporarily replace the provider and close the registration to restore the
previous one:

```java
ServerLoadManager loads = client.getServerLoadManager();
try (ServerLoadManager.ProviderRegistration ignored =
        loads.setProvider(() -> new ServerLoad(activeMatches, acceptingPlayers))) {
    ServerLoadSnapshot snapshot = loads.refresh().await();
    if (snapshot.isStale(Instant.now())) {
        throw new IllegalStateException("Load expired");
    }
}
```

Use `loads.getCurrent()` to read the last snapshot. Placement rejects stale load and servers whose
provider reports `acceptingQueueAssignments=false`.

### Atomic placement

On Paper, public capacity counts non-staff; hard capacity counts everyone, including spectators.
Staff status is evaluated from `echo.staff` on Velocity and rechecked on the destination.
Grant it explicitly on both platforms; Paper does not grant it to operators by default.
Callers cannot pass a staff/bypass flag in a placement request. The destination gate also covers
initial joins, manual switches, and queue transfers. Online players, pending connections, and
unarrived reserved members share one physical occupancy count without double-counting arrivals.
Game participant load and `acceptingQueueAssignments` remain independent game-readiness inputs.

Paper captures immutable occupancy and permission snapshots on its main thread. An ordered Redis
worker runs admission and snapshot writes off-thread, with at most two operations queued. Periodic
publication never waits for Redis; a saturated queue drops that sample and retries on the next sample.
Login and join wait at most 100 ms for Redis work before refusing admission. A late completion never
authorizes the refused connection; the next ordered snapshot clears its conservatively held seat.
Destination lock acquisition does not wait for contention. Other placement operations wait at most
one second for lock contention and fail exceptionally when busy. Redis command/network timeouts
still come from the Connector client, independently of these limits; the lock watchdog remains enabled
so a slow command cannot outlive a fixed lease and break mutual exclusion. Worker shutdown does not wait.

Read real occupancy with `Server.getAdmission()` and proxy counts with `Proxy.getLoad()`.
Redis snapshots have a five-second validity window; missing or stale snapshots are unknown,
not zero. Future-dated snapshots are also invalid. `ServerPlacement.inspectServer()` exposes
reserved non-staff seats and physical headroom; check freshness before using those values.
Velocity uses a 475-player scale-out threshold, not a 120-player admission cap.

#### Agones autoscaler telemetry

With Agones enabled, both runtime plugins call SDK `SetAnnotation` using key `echo-telemetry`
once per second under normal tick/scheduler operation. The sidecar writes the GameServer annotation
`agones.dev/sdk-echo-telemetry`. The entire value is one atomic JSON object:

```json
{"version":1,"sampledAt":1788609600,"connectedPlayers":4,"publicPlayers":3,"publicCapacity":100}
```

`sampledAt` is Unix seconds sampled with the counts, `connectedPlayers` is the real online total,
and `publicPlayers` is the non-staff subset. Pending joins and reservations are not connected players.
`publicCapacity` is the configured Paper public limit or the proxy's 475-player threshold.
Redis publication is retained; SDK publishing does not require additional Kubernetes RBAC and
does not depend on the Redis write succeeding. Only one SDK annotation request is in flight at once.

Autoscaler consumers must reject missing/malformed data, unsupported versions, invalid counts,
future timestamps (`sampledAt > now`), and samples older than 30 seconds (`now - sampledAt > 30`).
Rejected data is unknown and must not be interpreted as an empty server.

Echo publishes measurements, not fleet-scaling decisions. Fleet selection, aggregation, thresholds,
cooldowns, and scale-in policy belong to the external controller. Admission requires trusted Redis
writers, authenticated player forwarding, and the destination plugin on every backend.

#### Coordinated upgrade from 7.0

New code reads deployed six-field leases conservatively as non-staff. New leases include a
seventh classification field that old workers cannot read. Do not mix old and new placement writers.

1. Pause every placement producer, including queue workers and initial-join reservation handlers.
2. Finish in-flight transfers and release or let their leases expire.
3. Roll Paper destinations and all placement workers, including Velocity and standalone `core` consumers, together.
4. Verify runtime versions, fresh admission snapshots, and the SDK annotation before resuming producers.

Publication alone does not coordinate live fleets; the deployment owner must perform this sequence.

#### Placement API

`ServerPlacement` reserves capacity for a whole group in one Redis operation. Requests are
idempotent by request ID, reservations expire unless renewed, and only the token-owning reservation
can renew or release its slots:

```java
ServerPlacement placement = client.getServerPlacement();
ServerPlacement.Request request = new ServerPlacement.Request(
        "party-42",
        Set.of(playerOne, playerTwo, playerThree, playerFour),
        Set.of("game-1", "game-2"),
        Map.of(new PropertyKey<String>("mode"), "ranked"),
        ServerPlacement.Policy.FILL_MOST_LOADED,
        Duration.ofSeconds(30));

ServerPlacement.Reservation reservation = placement.reserve(request).await().orElseThrow();
reservation = placement.renew(reservation, Duration.ofSeconds(30)).await().orElseThrow();
placement.release(reservation).await();
```

Candidates must be registered, alive, active, fresh, accepting assignments, match all exact
properties, and have enough free `placement_capacity`. Use `SPREAD_LEAST_LOADED` to spread groups.
Operations can be inspected with `listActiveReservations`, `findActiveReservation`, `inspectServer`,
and `explain`; explanations include a rejection reason for every candidate.

### On-demand servers

Application code can depend on the protocol-neutral `ondemand` module:

```java
PropertyKey<UUID> OWNER = new PropertyKey<>("owner");
ServerRequest request = new ServerRequest(
        "match-42",
        "bedwars",
        Map.of(OWNER, ownerId));
ServerHandle server = onDemandServers.acquire(request).join();
onDemandServers.terminate(server).join();
```

The Agones adapter makes acquisition idempotent, waits for the allocated GameServer to become
active in Echo, writes the requested Echo properties before returning it, and deletes allocations
that fail to register before the configured timeout. A two-argument `ServerRequest` constructor is
available when no initial properties are needed. `ServerHandle.requestId()` links a handle back to
the idempotent request.

Use `OnDemandServers.load()` when an adapter was registered by the host. Administration supports
`listAllocations`, `getAllocation`, termination by request ID, and `reconcile` to compare the
orchestrator allocation with its live Echo resource.

Create an Agones allocation adapter inside Kubernetes with:

```java
try (AgonesOnDemandServers agones = AgonesOnDemandServers.inCluster(
        client,
        Map.of("agones.dev/fleet", "bedwars"),
        Duration.ofMinutes(2))) {
    ServerHandle server = agones.acquire(new ServerRequest("match-42", "bedwars")).join();
}
```

The pod needs an in-cluster service account with access to Agones `GameServerAllocations` and
`GameServers`, Kubernetes service environment variables and service-account files, and the Agones
SDK HTTP sidecar. `AgonesGameServerLifecycle.inPod(...)` covers self-allocated long-lived servers,
externally allocated servers, and annotation-driven draining; close lifecycle and allocation
adapters during shutdown.

### Queues

The `queue` module combines Echo, Redis Connector, on-demand allocation, and atomic placement. It
stores recoverable request state in Redis and transfers only after the target Paper server accepts
the placement:

```java
QueueDefinition ranked = new QueueDefinition(
        new QueueId("ranked"),
        "bedwars",
        Map.of(new PropertyKey<String>("mode"), "ranked"),
        ServerPlacement.Policy.FILL_MOST_LOADED);

try (QueueService queue = new RedisQueue(
        redisConnection,
        client,
        onDemandServers,
        client.getServerPlacement(),
        List.of(ranked),
        QueueOptions.defaults())) {
    queue.start().join();
    QueueRequest request = new QueueRequest(
            "party-42", ranked.id(), Set.of(playerOne, playerTwo));
    QueueRequestStatus status = queue.enqueue(request).join();
}
```

Request states are `QUEUED`, `CLAIMED`, `PREPARED`, `TRANSFERRING`, `COMPLETED`, `FAILED`, and
`CANCELLED`. `QueueService.load()` accesses a host-registered service; `get` and `cancel` manage
requests. Its administration API lists queues/tickets and supports pause, resume, wake, retry, and
terminal-record purging. Paper discovers a custom `QueuePlacementPreparer` through Bukkit's
services manager; return `Decision.accept()` or `Decision.reject(reason)` before transfer.

## Usage

### Accessing the client

```java
EchoClient client = Echo.getClient();
```

### Async and blocking calls

Core API operations generally return `EchoFuture<T>`, which extends `CompletableFuture<T>` with an
`.await()` method. Queue, on-demand, and Agones orchestration operations return standard
`CompletableFuture<T>` and can use `join`, `get`, or normal completion stages:

```java
// Async
client.getUserById(uuid).thenAccept(userOpt -> {
    userOpt.ifPresent(user -> System.out.println("Found: " + user.getId()));
});

// Blocking
Optional<User> user = client.getUserById(uuid).await();
```

### Querying resources

```java
// Users
Optional<User> user = client.getUserById(uuid).await();
Optional<User> user = client.getUserByUsername("Steve").await();
Map<UUID, Long> allUsers = client.getAllUsers().await();

// Servers
Optional<Server> server = client.getServerById("lobby-1").await();
Map<String, Long> servers = client.getServers().await();

// Proxies
Optional<Proxy> proxy = client.getProxyById("proxy-eu").await();
```

### Properties

All resources (users, servers, proxies) support type-safe key-value properties stored in Redis:

```java
// Define a typed key
PropertyKey<Integer> LEVEL = new PropertyKey<>("level");

// Set and get
user.setProperty(LEVEL, 42).await();
Optional<Integer> level = user.getProperty(LEVEL).await();

// TTL support
user.setExpire(LEVEL, Instant.now().plusSeconds(3600)).await();

// Built-in user properties
Optional<String> username = user.getUsername().await();
Optional<String> serverId = user.getCurrentServerId().await();
Optional<Server> server = user.getCurrentServer().await();
```

### Messaging

#### Sending messages

Create custom messages by extending `EchoMessage`:

```java
public class AlertMessage extends EchoMessage {
    private String text;
    
    public AlertMessage() {} // Required for deserialization
    
    public AlertMessage(String text) {
        this.text = text;
    }
    
    public String getText() { return text; }
}
```

Send messages with convenience methods:

```java
// To a specific server or proxy
new AlertMessage("Hello!").sendToServer("lobby-1");
new AlertMessage("Hello!").sendToProxy("proxy-eu");

// Using MessageTarget for more control
MessageTarget target = MessageTarget.servers("lobby-1", "lobby-2");
new AlertMessage("Hello!").sendTo(target);

// To everyone on the network (instant, no network call)
new AlertMessage("Broadcast!").sendTo(MessageTarget.everyone());
```

#### Receiving messages

Subscribe with typed handlers - no `instanceof` checks needed:

```java
MessagingProvider messaging = client.getMessagingProvider();

// Typed subscription
messaging.subscribe("my-topic", AlertMessage.class, alert -> {
    System.out.println("Received: " + alert.getText());
});
```

#### Request / Response

Use `request` for a bounded exchange. It registers the reply waiter before publishing, avoiding
lost fast replies:

```java
MyRequest request = new MyRequest("data");
request.setReplyTopic(client.getLocalTopic());
client.getMessagingProvider()
        .request(
                MessageTarget.server("lobby-1").getTargets().iterator().next(),
                request,
                MyResponse.class,
                Duration.ofSeconds(10))
        .thenAccept(response -> System.out.println("Got response: " + response.getResult()));
```

On the receiving side, reply to a message:

```java
messaging.subscribe("my-topic", MyRequest.class, request -> {
    MyResponse response = new MyResponse("ok");
    request.reply(response);
});
```

### Server switching

```java
// Transfer a player to another server
User user = client.getUserById(uuid).await().orElseThrow();
ServerSwitchRequest.PlayerResponse response = user.tryConnectToServer("survival-1").await();

if (response.isSuccessful()) {
    System.out.println("Player transferred!");
}

// Transfer to a proxy
Proxy targetProxy = client.getProxyById("proxy-us").await().orElseThrow();
user.tryConnectToProxy(targetProxy).await();
```

`tryConnectToServer(String)` now has a ten-second default timeout. Use
`tryConnectToServer(String, Duration)` to set it explicitly. Failures distinguish a missing,
unavailable, or unregistered target, a disconnected player, a timeout, and an internal error.
`User.getSessionId()` exposes the current login session; integrations that manage user records
should use the session-aware `EchoClient.createUser` and `destroyUser` overloads so stale disconnect
events cannot delete a newer session.

### Remote administration

`RemoteAdministration` sends a bounded request to one exact resource and validates both the target
and deadline. Resource actions are `PING`, `REFRESH_LOAD`, `DRAIN`, `ACTIVATE`, and `SHUTDOWN`:

```java
RemoteAdministration administration = new RemoteAdministration(client);
ResourceControlRequest request = new ResourceControlRequest(
        ResourceControlRequest.Action.DRAIN,
        EchoResourceType.SERVER,
        "game-1",
        Duration.ofMinutes(10),
        "deployment");

ResourceControlRequest.Response response =
        administration.control(request, Duration.ofSeconds(5)).await();
administration.disconnect(user, "maintenance", Duration.ofSeconds(5)).await();
```

Always inspect the response status: a delivered request can still be rejected as invalid, expired,
wrongly targeted, disallowed, unsupported, timed out, or failed.

### Administration commands

Paper registers `/echo` and `/echoserver`; Velocity registers `/echo` and `/echoproxy`. The shared
command tree exposes:

| Group | Operations |
|-------|------------|
| General | `help`, `version`, `status`, and resource/health/queue/placement monitors |
| Resources | `server` and `proxy` list, info, ping, properties, drain, activate, shutdown; server load refresh |
| Users | list, info, send, disconnect |
| Queues | list, info, tickets, enqueue, cancel, pause, resume, wake, retry, requeue, purge |
| Placement and allocation | status/explain/reservations/reserve/renew/release and list/info/acquire/reconcile/terminate |

Each command has the corresponding `echo.command.<path>` permission. State-changing and destructive
commands print the exact retry command and require `--confirm`.

### Platform lifecycle

Paper automatically publishes player-count load and placement capacity, refreshes load on joins and
quits, accepts remote control requests, and exposes `beginDrain`, `isDraining`, `refreshLoad`,
`activate`, and `requestShutdown`. Velocity rejects logins while starting or draining, removes
draining servers from routing, restores active servers, accepts remote control and disconnect
requests, and exposes drain/activate/shutdown plus session-aware `disconnectPlayer` overloads.

## Healthcheck

Echo includes a built-in healthcheck system that detects crashed or unresponsive nodes and automatically cleans up their resources.

### How it works

1. **Heartbeat**: Every node periodically writes a Redis key (`heartbeat:<type>:<id>`) with a TTL. If the node crashes, the key expires automatically.
2. **Scanner**: Nodes with cleanup enabled periodically scan all registered servers and proxies. If a heartbeat is missing, the resource is marked as **suspect**.
3. **Double-check**: A suspected resource must be missing its heartbeat for two consecutive scans before cleanup is triggered. This avoids false positives from temporary network issues.
4. **Distributed cleanup**: When a dead resource is confirmed, the detecting node acquires a Redis distributed lock before cleaning up, ensuring only one node performs the cleanup even in multi-proxy setups.

### Cleanup actions

When a dead resource is cleaned up:
- It is removed from `servers:map` or `proxies:map`
- If it's a server, a `ServerStatusNotification(UNREGISTERED)` is sent to all proxies
- Its properties and address are deleted
- Orphaned users (whose `current_server_id` or `current_proxy_id` still points to the dead resource) are destroyed. Users that were already redirected to another server are preserved.

### Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `ECHO_HEARTBEAT_TTL` | `30` | Heartbeat TTL in seconds |
| `ECHO_HEARTBEAT_INTERVAL` | `10` | Heartbeat renewal interval in seconds |
| `ECHO_SCAN_INTERVAL` | `15` | Dead resource scan interval in seconds |
| `ECHO_HEALTHCHECK_CLEANUP_ENABLED` | `false` | Enable cleanup on servers (always active on proxies) |

> **Note**: Proxies always perform cleanup. For server-only infrastructures (no proxy), set `ECHO_HEALTHCHECK_CLEANUP_ENABLED=true` on at least one server.

## Migrating from 6.x

### Replace removed APIs

| 6.x | 7.0 |
|-----|-----|
| `client.newMessageTargetBuilder()` | `MessageTarget.builder()` |
| `CacheProviderFactory` | `Supplier<? extends CacheProvider>` |
| `MessagingProviderFactory` | `Supplier<? extends MessagingProvider>` |
| `Pair`, `NullableUtils`, `FutureUtils` | JDK records, `Optional`, and `CompletableFuture` methods |
| `MapUtils.map` / `MapFunction` | Standard collection and stream operations |

`MessageTarget.Builder` is now a final concrete class; custom implementations and mocks must be
removed. `api` no longer exports fastutil transitively, so consumers using it directly must declare
their own dependency. Paper's `JoinListener` now requires `EchoPaper` and `ServerLoadManager`;
Velocity's listener takes login-state/session suppliers instead of `ProxyServer`.

### Review changed behavior

- Server switching is bounded and reports explicit target, player, timeout, and internal failures.
- Proxy transfers route through the user's current proxy.
- User cleanup is session-aware and maintains server/proxy membership.
- Initial properties are persisted before a resource becomes discoverable.
- Server shutdown advertises `DRAINING` before unregistering.
- Non-positive Redis lock leases use Redisson watchdog renewal; subscription cancellation removes only Echo's listener.

## Building

```bash
./gradlew build
```

Artifacts are produced in each module's `build/libs/` directory. The Paper and Velocity modules produce shadow JARs ready to be used as plugins.

## License

See [LICENSE](LICENSE) for details.
