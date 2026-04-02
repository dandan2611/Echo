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
    <version>5.0.0</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven("https://nexus.codinbox.fr/repository/maven-releases/")
}

dependencies {
    implementation("fr.codinbox.echo:api:5.0.0")
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

## Building

```bash
./gradlew build
```

Artifacts are produced in each module's `build/libs/` directory. The Paper and Velocity modules produce shadow JARs ready to be used as plugins.

## License

See [LICENSE](LICENSE) for details.
