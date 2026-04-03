# Echo

A distributed messaging and resource management library for Minecraft server networks, built on Redis.

Echo provides a unified API to track players, servers, and proxies across your entire infrastructure, with built-in pub/sub messaging, property storage, and server switching capabilities.

## Features

- **Network-wide resource tracking** - Query and manage users, servers, and proxies from any node
- **Distributed messaging** - Publish/subscribe messaging with typed handlers and request/response patterns
- **Property storage** - Attach arbitrary key-value properties with optional TTL to any resource
- **Server switching** - Transfer players between servers and proxies with status feedback
- **Async-first API** - All operations return `EchoFuture`, with a simple `.await()` for blocking calls
- **Platform integrations** - Ready-to-use plugins for [Paper](https://papermc.io/) and [Velocity](https://velocitypowered.com/)

## Requirements

- Java 21+
- Redis server
- [Connector](https://github.com/dandan2611/Connector) library for Redis connection management

## Modules

| Module | Description |
|--------|-------------|
| `api` | Public API interfaces and contracts |
| `core` | Core implementation backed by Redis (Redisson) |
| `paper` | Paper server plugin - auto-registers players and servers |
| `velocity` | Velocity proxy plugin - handles server switching and player routing |

## Installation

### Maven

```xml
<repository>
    <id>codinbox-releases</id>
    <url>https://nexus.codinbox.fr/repository/maven-releases/</url>
</repository>
```

```xml
<dependency>
    <groupId>fr.codinbox.echo</groupId>
    <artifactId>api</artifactId>
    <version>5.1.0</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven("https://nexus.codinbox.fr/repository/maven-releases/")
}

dependencies {
    implementation("fr.codinbox.echo:api:5.1.0")
}
```

## Configuration

Echo reads its configuration from environment variables:

| Variable | Description | Example |
|----------|-------------|---------|
| `ECHO_RESOURCE_TYPE` | The type of this node | `SERVER` or `PROXY` |
| `ECHO_RESOURCE_ID` | Unique identifier for this node | `lobby-1`, `proxy-eu` |
| `ECHO_RESOURCE_ADDRESS` | The address of this node | `127.0.0.1:25565` |

A Redis connection named `ECHO` must be registered through the Connector library.

## Usage

### Accessing the client

```java
EchoClient client = Echo.getClient();
```

### Async and blocking calls

Every API method returns an `EchoFuture<T>`, which extends `CompletableFuture<T>` with an `.await()` method. You choose your execution model:

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

Use `awaitReply` for composable request/response patterns:

```java
MyRequest request = new MyRequest("data");
request.sendToServer("lobby-1");

// Wait for a typed reply
request.awaitReply(MyResponse.class).thenAccept(response -> {
    System.out.println("Got response: " + response.getResult());
});
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

## Building

```bash
./gradlew build
```

Artifacts are produced in each module's `build/libs/` directory. The Paper and Velocity modules produce shadow JARs ready to be used as plugins.

## License

See [LICENSE](LICENSE) for details.
