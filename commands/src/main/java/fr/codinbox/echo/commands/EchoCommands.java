package fr.codinbox.echo.commands;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.administration.RemoteAdministration;
import fr.codinbox.echo.api.exception.UnknownResourceException;
import fr.codinbox.echo.api.exception.user.UserHasNoProxyException;
import fr.codinbox.echo.api.local.EchoResourceType;
import fr.codinbox.echo.api.messaging.impl.ResourceControlRequest;
import fr.codinbox.echo.api.messaging.impl.UserDisconnectRequest;
import fr.codinbox.echo.api.property.PropertyHolder;
import fr.codinbox.echo.api.property.PropertyKey;
import fr.codinbox.echo.api.proxy.Proxy;
import fr.codinbox.echo.api.server.Address;
import fr.codinbox.echo.api.server.Joinable;
import fr.codinbox.echo.api.server.Server;
import fr.codinbox.echo.api.server.ServerLoadSnapshot;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import fr.codinbox.echo.api.user.User;
import fr.codinbox.echo.ondemand.OnDemandAdministration;
import fr.codinbox.echo.ondemand.OnDemandServers;
import fr.codinbox.echo.ondemand.ServerHandle;
import fr.codinbox.echo.ondemand.ServerRequest;
import fr.codinbox.echo.queue.QueueAdministration;
import fr.codinbox.echo.queue.QueueId;
import fr.codinbox.echo.queue.QueueRequest;
import fr.codinbox.echo.queue.QueueRequestStatus;
import fr.codinbox.echo.queue.QueueService;
import net.kyori.adventure.text.Component;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Default;
import org.incendo.cloud.annotations.Flag;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** Platform-neutral Echo administration commands parsed by Cloud Annotations. */
public final class EchoCommands<S> {

    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_LEASE = Duration.ofSeconds(15);
    private static final int SUGGESTION_LIMIT = 100;
    private static final PropertyKey<String> PROPERTY_SERVER_TYPE = new PropertyKey<>("server_type");
    private static final Executor VIRTUAL_THREADS = Thread::startVirtualThread;

    private final EchoClient echo;
    private final RemoteAdministration remote;
    private final CommandAudience<S> audience;
    private final CommandFormatter format;
    private final String rootSyntax;
    private final String root;
    private final Supplier<QueueService> queueLoader;
    private final Supplier<OnDemandServers> onDemandLoader;
    private final Logger logger;
    private final ConcurrentMap<String, ServerPlacement.Reservation> reservations = new ConcurrentHashMap<>();

    public EchoCommands(EchoClient echo, CommandAudience<S> audience, String rootSyntax) {
        this(echo, audience, rootSyntax, QueueService::load, OnDemandServers::load,
                Logger.getLogger(EchoCommands.class.getName()));
    }

    EchoCommands(EchoClient echo, CommandAudience<S> audience, String rootSyntax,
                 Supplier<QueueService> queueLoader, Supplier<OnDemandServers> onDemandLoader,
                 Logger logger) {
        this.echo = Objects.requireNonNull(echo, "echo");
        this.remote = new RemoteAdministration(echo);
        this.audience = Objects.requireNonNull(audience, "audience");
        this.format = new CommandFormatter();
        this.rootSyntax = requireRoot(rootSyntax);
        this.root = this.rootSyntax.split("\\|", 2)[0];
        this.queueLoader = Objects.requireNonNull(queueLoader, "queueLoader");
        this.onDemandLoader = Objects.requireNonNull(onDemandLoader, "onDemandLoader");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Installs root aliases through Cloud's annotation string processor and registers all handlers. */
    public Collection<org.incendo.cloud.Command<S>> register(AnnotationParser<S> parser) {
        Objects.requireNonNull(parser, "parser");
        var previous = parser.stringProcessor();
        parser.stringProcessor(input -> previous.processString(input).replace("${root}", this.rootSyntax));
        return parser.parse(this);
    }

    @Command("${root}")
    @Permission("echo.command.root")
    public CompletableFuture<Void> root(CommandContext<S> context) {
        return send(context, this.format.success("Echo administration is available. Use " + this.root + " help."));
    }

    @Command("${root} help")
    @Permission("echo.command.help")
    public CompletableFuture<Void> help(CommandContext<S> context) {
        return send(context, this.format.list("Echo commands", List.of(
                "status and monitoring", "server and proxy control", "user routing",
                "queue and placement", "on-demand allocation")));
    }

    @Command("${root} version")
    @Permission("echo.command.version")
    public CompletableFuture<Void> version(CommandContext<S> context) {
        String version = Optional.ofNullable(EchoCommands.class.getPackage().getImplementationVersion())
                .orElse("development");
        return send(context, this.format.rows("Echo version", Map.of("version", version)));
    }

    @Command("${root} status")
    @Permission("echo.command.status")
    public CompletableFuture<Void> status(CommandContext<S> context) {
        return execute(context, this::networkCounts, counts -> this.format.rows("Echo status", Map.of(
                "local type", this.echo.getCurrentResourceType(),
                "local id", this.echo.getCurrentResourceId().orElse("unconfigured"),
                "servers", counts.servers(), "proxies", counts.proxies(), "users", counts.users())));
    }

    @Command("${root} monitor resources")
    @Permission("echo.command.monitor.resources")
    public CompletableFuture<Void> monitorResources(CommandContext<S> context) {
        return execute(context, () -> this.echo.getServers().thenCombine(this.echo.getProxies(), ResourceMaps::new)
                .thenCombine(this.echo.getAllUsers(), (resources, users) -> {
                    List<String> lines = new ArrayList<>();
                    resources.servers().entrySet().stream().sorted(Map.Entry.comparingByKey())
                            .forEach(entry -> lines.add("server " + entry.getKey() + " "
                                    + this.format.instant(entry.getValue())));
                    resources.proxies().entrySet().stream().sorted(Map.Entry.comparingByKey())
                            .forEach(entry -> lines.add("proxy " + entry.getKey() + " "
                                    + this.format.instant(entry.getValue())));
                    lines.add("users " + users.size());
                    return lines;
                }), lines -> this.format.list("Echo resources", lines));
    }

    @Command("${root} monitor health")
    @Permission("echo.command.monitor.health")
    public CompletableFuture<Void> monitorHealth(CommandContext<S> context) {
        return execute(context, this::health, results -> this.format.list("Resource health",
                results.stream().map(result -> result.type() + " " + result.id() + ": "
                        + (result.alive() ? "alive" : "missing")).toList()));
    }

    @Command("${root} monitor queues")
    @Permission("echo.command.monitor.queues")
    public CompletableFuture<Void> monitorQueues(CommandContext<S> context) {
        return execute(context, () -> queueAdministration().listQueues(), this::queueOverviewList);
    }

    @Command("${root} monitor placement")
    @Permission("echo.command.monitor.placement")
    public CompletableFuture<Void> monitorPlacement(CommandContext<S> context) {
        return execute(context, () -> placement().listActiveReservations(), this::reservationList);
    }

    @Command("${root} server list")
    @Permission("echo.command.server.list")
    public CompletableFuture<Void> serverList(CommandContext<S> context) {
        return execute(context, this.echo::getServers, servers -> this.format.list("Servers",
                servers.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + " " + this.format.instant(entry.getValue())).toList()));
    }

    @Command("${root} server info <id>")
    @Permission("echo.command.server.info")
    public CompletableFuture<Void> serverInfo(CommandContext<S> context,
                                              @Argument(value = "id", suggestions = "servers") String id) {
        return execute(context, () -> requireServer(id).thenCompose(server -> address(server)
                .thenCombine(server.getConnectedUsers(), (address, users) -> new ResourceInfo(
                        server.getId(), address, users.size(), ""))
                .thenCombine(server.getAvailability(), (info, availability) -> new ResourceInfo(
                        info.id(), info.address(), info.users(), availability.toString()))), this::resourceInfo);
    }

    @Command("${root} server ping <id>")
    @Permission("echo.command.server.ping")
    public CompletableFuture<Void> serverPing(CommandContext<S> context,
                                              @Argument(value = "id", suggestions = "servers") String id) {
        return execute(context, () -> control(EchoResourceType.SERVER, id, ResourceControlRequest.Action.PING),
                this::controlResult);
    }

    @Command("${root} server properties <id>")
    @Permission("echo.command.server.properties")
    public CompletableFuture<Void> serverProperties(CommandContext<S> context,
                                                    @Argument(value = "id", suggestions = "servers") String id) {
        return execute(context, () -> requireServer(id).thenCompose(this::properties),
                values -> propertyList("Server properties", values));
    }

    @Command("${root} server property <id> <key>")
    @Permission("echo.command.server.property")
    public CompletableFuture<Void> serverProperty(CommandContext<S> context,
                                                  @Argument(value = "id", suggestions = "servers") String id,
                                                  @Argument("key") String key) {
        return execute(context, () -> requireServer(id).thenCompose(server -> property(server, key)),
                value -> propertyValue("Server property", value));
    }

    @Command("${root} server load <id>")
    @Permission("echo.command.server.load")
    public CompletableFuture<Void> serverLoad(CommandContext<S> context,
                                              @Argument(value = "id", suggestions = "servers") String id) {
        return execute(context, () -> requireServer(id).thenCompose(Server::getLoad), this::serverLoadResult);
    }

    @Command("${root} server load refresh <id>")
    @Permission("echo.command.server.load.refresh")
    public CompletableFuture<Void> serverLoadRefresh(CommandContext<S> context,
                                                     @Argument(value = "id", suggestions = "servers") String id) {
        return mutate(context, "server.load.refresh", id,
                () -> control(EchoResourceType.SERVER, id, ResourceControlRequest.Action.REFRESH_LOAD),
                this::controlResult, response -> response.getStatus().name());
    }

    @Command("${root} server drain <id> [minutes]")
    @Permission("echo.command.server.drain")
    public CompletableFuture<Void> serverDrain(CommandContext<S> context,
                                               @Argument(value = "id", suggestions = "servers") String id,
                                               @Argument("minutes") @Default("30") int minutes,
                                               @Flag("confirm") boolean confirm) {
        String command = this.root + " server drain " + id + " " + minutes + " --confirm";
        return confirm(context, confirm, command, () -> mutate(context, "server.drain",
                "server/" + id + " duration=" + minutes + "m",
                () -> drain(EchoResourceType.SERVER, id, minutes), this::controlResult,
                response -> response.getStatus().name()));
    }

    @Command("${root} server activate <id>")
    @Permission("echo.command.server.activate")
    public CompletableFuture<Void> serverActivate(CommandContext<S> context,
                                                  @Argument(value = "id", suggestions = "servers") String id,
                                                  @Flag("confirm") boolean confirm) {
        return confirm(context, confirm, this.root + " server activate " + id + " --confirm",
                () -> mutate(context, "server.activate", id,
                        () -> control(EchoResourceType.SERVER, id, ResourceControlRequest.Action.ACTIVATE),
                        this::controlResult, response -> response.getStatus().name()));
    }

    @Command("${root} server shutdown <id>")
    @Permission("echo.command.server.shutdown")
    public CompletableFuture<Void> serverShutdown(CommandContext<S> context,
                                                  @Argument(value = "id", suggestions = "servers") String id,
                                                  @Flag("confirm") boolean confirm) {
        return confirm(context, confirm, this.root + " server shutdown " + id + " --confirm",
                () -> mutate(context, "server.shutdown", id,
                        () -> control(EchoResourceType.SERVER, id, ResourceControlRequest.Action.SHUTDOWN),
                        this::controlResult, response -> response.getStatus().name()));
    }

    @Command("${root} proxy list")
    @Permission("echo.command.proxy.list")
    public CompletableFuture<Void> proxyList(CommandContext<S> context) {
        return execute(context, this.echo::getProxies, proxies -> this.format.list("Proxies",
                proxies.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + " " + this.format.instant(entry.getValue())).toList()));
    }

    @Command("${root} proxy info <id>")
    @Permission("echo.command.proxy.info")
    public CompletableFuture<Void> proxyInfo(CommandContext<S> context,
                                             @Argument(value = "id", suggestions = "proxies") String id) {
        return execute(context, () -> requireProxy(id).thenCompose(proxy -> address(proxy)
                .thenCombine(proxy.getConnectedUsers(), (address, users) -> new ResourceInfo(
                        proxy.getId(), address, users.size(), "n/a"))), this::resourceInfo);
    }

    @Command("${root} proxy ping <id>")
    @Permission("echo.command.proxy.ping")
    public CompletableFuture<Void> proxyPing(CommandContext<S> context,
                                             @Argument(value = "id", suggestions = "proxies") String id) {
        return execute(context, () -> control(EchoResourceType.PROXY, id, ResourceControlRequest.Action.PING),
                this::controlResult);
    }

    @Command("${root} proxy properties <id>")
    @Permission("echo.command.proxy.properties")
    public CompletableFuture<Void> proxyProperties(CommandContext<S> context,
                                                   @Argument(value = "id", suggestions = "proxies") String id) {
        return execute(context, () -> requireProxy(id).thenCompose(this::properties),
                values -> propertyList("Proxy properties", values));
    }

    @Command("${root} proxy property <id> <key>")
    @Permission("echo.command.proxy.property")
    public CompletableFuture<Void> proxyProperty(CommandContext<S> context,
                                                 @Argument(value = "id", suggestions = "proxies") String id,
                                                 @Argument("key") String key) {
        return execute(context, () -> requireProxy(id).thenCompose(proxy -> property(proxy, key)),
                value -> propertyValue("Proxy property", value));
    }

    @Command("${root} proxy drain <id> [minutes]")
    @Permission("echo.command.proxy.drain")
    public CompletableFuture<Void> proxyDrain(CommandContext<S> context,
                                              @Argument(value = "id", suggestions = "proxies") String id,
                                              @Argument("minutes") @Default("30") int minutes,
                                              @Flag("confirm") boolean confirm) {
        String command = this.root + " proxy drain " + id + " " + minutes + " --confirm";
        return confirm(context, confirm, command, () -> mutate(context, "proxy.drain",
                "proxy/" + id + " duration=" + minutes + "m",
                () -> drain(EchoResourceType.PROXY, id, minutes), this::controlResult,
                response -> response.getStatus().name()));
    }

    @Command("${root} proxy activate <id>")
    @Permission("echo.command.proxy.activate")
    public CompletableFuture<Void> proxyActivate(CommandContext<S> context,
                                                 @Argument(value = "id", suggestions = "proxies") String id,
                                                 @Flag("confirm") boolean confirm) {
        return confirm(context, confirm, this.root + " proxy activate " + id + " --confirm",
                () -> mutate(context, "proxy.activate", id,
                        () -> control(EchoResourceType.PROXY, id, ResourceControlRequest.Action.ACTIVATE),
                        this::controlResult, response -> response.getStatus().name()));
    }

    @Command("${root} proxy shutdown <id>")
    @Permission("echo.command.proxy.shutdown")
    public CompletableFuture<Void> proxyShutdown(CommandContext<S> context,
                                                 @Argument(value = "id", suggestions = "proxies") String id,
                                                 @Flag("confirm") boolean confirm) {
        return confirm(context, confirm, this.root + " proxy shutdown " + id + " --confirm",
                () -> mutate(context, "proxy.shutdown", id,
                        () -> control(EchoResourceType.PROXY, id, ResourceControlRequest.Action.SHUTDOWN),
                        this::controlResult, response -> response.getStatus().name()));
    }

    @Command("${root} user list")
    @Permission("echo.command.user.list")
    public CompletableFuture<Void> userList(CommandContext<S> context) {
        return execute(context, this.echo::getAllUsers, users -> this.format.list("Users",
                users.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + " " + this.format.instant(entry.getValue())).toList()));
    }

    @Command("${root} user info <user>")
    @Permission("echo.command.user.info")
    public CompletableFuture<Void> userInfo(CommandContext<S> context,
                                            @Argument(value = "user", suggestions = "users") String user) {
        return execute(context, () -> resolveUser(user).thenCompose(this::userDetails), details -> this.format.rows(
                "User", Map.of("id", details.id(), "username", details.username(),
                        "proxy", details.proxy(), "server", details.server())));
    }

    @Command("${root} user send <user> <server>")
    @Permission("echo.command.user.send")
    public CompletableFuture<Void> userSend(CommandContext<S> context,
                                            @Argument(value = "user", suggestions = "users") String user,
                                            @Argument(value = "server", suggestions = "servers") String server) {
        return mutate(context, "user.send", user + "->" + server,
                () -> resolveUser(user).thenCompose(found -> found.tryConnectToServer(server, CONTROL_TIMEOUT)),
                response -> response.isSuccessful()
                        ? this.format.success("User sent to " + server + ".")
                        : this.format.error("User transfer failed: " + response.getStatus() + "."),
                response -> response.getStatus().name());
    }

    @Command("${root} user disconnect <user> [reason]")
    @Permission("echo.command.user.disconnect")
    public CompletableFuture<Void> userDisconnect(CommandContext<S> context,
                                                  @Argument(value = "user", suggestions = "users") String user,
                                                  @Argument("reason") @Default("Disconnected by an administrator") String reason,
                                                  @Flag("confirm") boolean confirm) {
        String command = this.root + " user disconnect " + user + " " + quote(reason) + " --confirm";
        return confirm(context, confirm, command, () -> mutate(context, "user.disconnect", user,
                () -> resolveUser(user).thenCompose(found -> this.remote.disconnect(found, reason, CONTROL_TIMEOUT)),
                 this::disconnectResult,
                response -> response.getStatus().name()));
    }

    @Command("${root} queue list")
    @Permission("echo.command.queue.list")
    public CompletableFuture<Void> queueList(CommandContext<S> context) {
        return execute(context, () -> queueAdministration().listQueues(), this::queueOverviewList);
    }

    @Command("${root} queue info <queue>")
    @Permission("echo.command.queue.info")
    public CompletableFuture<Void> queueInfo(CommandContext<S> context,
                                             @Argument(value = "queue", suggestions = "queues") String queue) {
        return execute(context, () -> queueAdministration().listQueues().thenCompose(overviews -> overviews.stream()
                .filter(overview -> overview.definition().id().value().equals(queue)).findFirst()
                .map(CompletableFuture::completedFuture)
                .orElseGet(() -> CompletableFuture.failedFuture(new IllegalArgumentException(
                        "Queue not found: " + queue)))), this::queueOverview);
    }

    @Command("${root} queue tickets <queue>")
    @Permission("echo.command.queue.tickets")
    public CompletableFuture<Void> queueTickets(CommandContext<S> context,
                                                @Argument(value = "queue", suggestions = "queues") String queue) {
        return execute(context, () -> queueAdministration().listTickets(new QueueId(queue)), tickets -> this.format.list(
                "Queue tickets", tickets.stream().map(this::ticketLine).toList()));
    }

    @Command("${root} queue ticket <queue> <request>")
    @Permission("echo.command.queue.ticket")
    public CompletableFuture<Void> queueTicket(CommandContext<S> context,
                                               @Argument(value = "queue", suggestions = "queues") String queue,
                                               @Argument("request") String request) {
        return execute(context, () -> queueService().get(new QueueId(queue), request), status -> status
                .map(this::queueStatus).orElseGet(() -> this.format.error("Queue ticket not found: " + request)));
    }

    @Command("${root} queue enqueue <queue> <request> <members>")
    @Permission("echo.command.queue.enqueue")
    public CompletableFuture<Void> queueEnqueue(CommandContext<S> context,
                                                @Argument(value = "queue", suggestions = "queues") String queue,
                                                @Argument("request") String request,
                                                @Argument("members") String members) {
        return mutate(context, "queue.enqueue", queue + "/" + request,
                () -> queueService().enqueue(new QueueRequest(request, new QueueId(queue), parseMembers(members))),
                status -> this.format.success("Queue request is " + status.state() + "."),
                status -> status.state().name());
    }

    @Command("${root} queue cancel <queue> <request>")
    @Permission("echo.command.queue.cancel")
    public CompletableFuture<Void> queueCancel(CommandContext<S> context,
                                               @Argument(value = "queue", suggestions = "queues") String queue,
                                               @Argument("request") String request,
                                               @Flag("confirm") boolean confirm) {
        String command = this.root + " queue cancel " + queue + " " + request + " --confirm";
        return confirm(context, confirm, command, () -> mutate(context, "queue.cancel", queue + "/" + request,
                () -> queueService().cancel(new QueueId(queue), request),
                changed -> changed ? this.format.success("Queue request cancelled.")
                        : this.format.warn("Queue request was not cancellable."), String::valueOf));
    }

    @Command("${root} queue pause <queue> <reason>")
    @Permission("echo.command.queue.pause")
    public CompletableFuture<Void> queuePause(CommandContext<S> context,
                                              @Argument(value = "queue", suggestions = "queues") String queue,
                                              @Argument("reason") String reason,
                                              @Flag("confirm") boolean confirm) {
        String command = this.root + " queue pause " + queue + " " + quote(reason) + " --confirm";
        return confirm(context, confirm, command, () -> mutate(context, "queue.pause", queue,
                () -> queueAdministration().pause(new QueueId(queue), reason),
                changed -> changed ? this.format.success("Queue paused.")
                        : this.format.warn("Queue was already paused."), String::valueOf));
    }

    @Command("${root} queue resume <queue>")
    @Permission("echo.command.queue.resume")
    public CompletableFuture<Void> queueResume(CommandContext<S> context,
                                               @Argument(value = "queue", suggestions = "queues") String queue,
                                               @Flag("confirm") boolean confirm) {
        return confirm(context, confirm, this.root + " queue resume " + queue + " --confirm",
                () -> mutate(context, "queue.resume", queue,
                        () -> queueAdministration().resume(new QueueId(queue)),
                        changed -> changed ? this.format.success("Queue resumed.")
                                : this.format.warn("Queue was not paused."), String::valueOf));
    }

    @Command("${root} queue wake <queue>")
    @Permission("echo.command.queue.wake")
    public CompletableFuture<Void> queueWake(CommandContext<S> context,
                                             @Argument(value = "queue", suggestions = "queues") String queue) {
        return mutate(context, "queue.wake", queue,
                () -> queueAdministration().wake(new QueueId(queue)),
                ignored -> this.format.success("Queue worker wake requested."), ignored -> "requested");
    }

    @Command("${root} queue retry <queue> <request>")
    @Permission("echo.command.queue.retry")
    public CompletableFuture<Void> queueRetry(CommandContext<S> context,
                                              @Argument(value = "queue", suggestions = "queues") String queue,
                                              @Argument("request") String request,
                                              @Flag("confirm") boolean confirm) {
        String command = this.root + " queue retry " + queue + " " + request + " --confirm";
        return confirm(context, confirm, command, () -> retry(context, "queue.retry", queue, request));
    }

    @Command("${root} queue requeue <queue> <request>")
    @Permission("echo.command.queue.requeue")
    public CompletableFuture<Void> queueRequeue(CommandContext<S> context,
                                                @Argument(value = "queue", suggestions = "queues") String queue,
                                                @Argument("request") String request,
                                                @Flag("confirm") boolean confirm) {
        String command = this.root + " queue requeue " + queue + " " + request + " --confirm";
        return confirm(context, confirm, command, () -> retry(context, "queue.requeue", queue, request));
    }

    @Command("${root} queue purge <queue> <olderThanMinutes>")
    @Permission("echo.command.queue.purge")
    public CompletableFuture<Void> queuePurge(CommandContext<S> context,
                                              @Argument(value = "queue", suggestions = "queues") String queue,
                                              @Argument("olderThanMinutes") long olderThanMinutes,
                                              @Flag("confirm") boolean confirm) {
        String command = this.root + " queue purge " + queue + " " + olderThanMinutes + " --confirm";
        return confirm(context, confirm, command, () -> mutate(context, "queue.purge",
                queue + " olderThan=" + olderThanMinutes + "m",
                () -> queueAdministration().purgeTerminal(new QueueId(queue), positiveMinutes(olderThanMinutes)),
                count -> this.format.success("Purged " + count + " terminal queue requests."), String::valueOf));
    }

    @Command("${root} placement status <server>")
    @Permission("echo.command.placement.status")
    public CompletableFuture<Void> placementStatus(CommandContext<S> context,
                                                   @Argument(value = "server", suggestions = "servers") String server) {
        return execute(context, () -> placement().inspectServer(server), status -> status
                .map(this::placementStatus).orElseGet(() -> this.format.error("Server not found: " + server)));
    }

    @Command("${root} placement explain <serverType> <members> [policy]")
    @Permission("echo.command.placement.explain")
    public CompletableFuture<Void> placementExplain(CommandContext<S> context,
                                                    @Argument("serverType") String serverType,
                                                    @Argument("members") String members,
                                                    @Argument("policy") @Default("SPREAD_LEAST_LOADED") String policy) {
        return execute(context, () -> {
            Set<UUID> parsedMembers = parseMembers(members);
            ServerPlacement.Policy parsedPolicy = policy(policy);
            String requestId = explainRequestId(serverType, parsedMembers, parsedPolicy);
            return placement().explain(placementRequest(requestId, serverType, parsedMembers,
                    parsedPolicy, DEFAULT_LEASE));
        }, this::placementExplanation);
    }

    @Command("${root} placement reservations")
    @Permission("echo.command.placement.reservations")
    public CompletableFuture<Void> placementReservations(CommandContext<S> context) {
        return execute(context, () -> placement().listActiveReservations(), this::reservationList);
    }

    @Command("${root} placement reservation <request>")
    @Permission("echo.command.placement.reservation")
    public CompletableFuture<Void> placementReservation(CommandContext<S> context,
                                                        @Argument(value = "request", suggestions = "placements") String request) {
        return execute(context, () -> placement().findActiveReservation(request), reservation -> reservation
                .map(this::reservation).orElseGet(() -> this.format.error("Reservation not found: " + request)));
    }

    @Command("${root} placement reserve <request> <serverType> <members> [policy] [leaseSeconds]")
    @Permission("echo.command.placement.reserve")
    public CompletableFuture<Void> placementReserve(CommandContext<S> context,
                                                    @Argument("request") String request,
                                                    @Argument("serverType") String serverType,
                                                    @Argument("members") String members,
                                                    @Argument("policy") @Default("SPREAD_LEAST_LOADED") String policy,
                                                    @Argument("leaseSeconds") @Default("15") long leaseSeconds) {
        return mutate(context, "placement.reserve", request, () -> placement().reserve(placementRequest(
                        request, serverType, parseMembers(members), policy(policy), positiveSeconds(leaseSeconds)))
                .thenApply(reservation -> reservation.map(value -> {
                    this.reservations.put(request, value);
                    return value;
                })), reservation -> reservation.map(this::reservation)
                .orElseGet(() -> this.format.warn("No eligible server.")),
                reservation -> reservation.isPresent() ? "reserved" : "unavailable");
    }

    @Command("${root} placement renew <request> [leaseSeconds]")
    @Permission("echo.command.placement.renew")
    public CompletableFuture<Void> placementRenew(CommandContext<S> context,
                                                  @Argument(value = "request", suggestions = "placements") String request,
                                                  @Argument("leaseSeconds") @Default("15") long leaseSeconds) {
        return mutate(context, "placement.renew", request, () -> {
            ServerPlacement.Reservation current = this.reservations.get(request);
            if (current == null)
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "Reservation is not held by this process: " + request));
            return placement().renew(current, positiveSeconds(leaseSeconds)).thenApply(renewed -> renewed.map(value -> {
                this.reservations.put(request, value);
                return value;
            }));
        }, renewed -> renewed.map(this::reservation)
                .orElseGet(() -> this.format.warn("Reservation is no longer active.")),
                renewed -> renewed.isPresent() ? "renewed" : "inactive");
    }

    @Command("${root} placement release <request>")
    @Permission("echo.command.placement.release")
    public CompletableFuture<Void> placementRelease(CommandContext<S> context,
                                                    @Argument(value = "request", suggestions = "placements") String request,
                                                    @Flag("confirm") boolean confirm) {
        return confirm(context, confirm, this.root + " placement release " + request + " --confirm",
                () -> mutate(context, "placement.release", request, () -> {
                    ServerPlacement.Reservation reservation = this.reservations.get(request);
                    if (reservation == null)
                        return CompletableFuture.failedFuture(new IllegalArgumentException(
                                "Reservation is not held by this process: " + request));
                    return placement().release(reservation).thenApply(released -> {
                        if (released)
                            this.reservations.remove(request, reservation);
                        return released;
                    });
                }, released -> released ? this.format.success("Reservation released.")
                        : this.format.warn("Reservation was absent or superseded."), String::valueOf));
    }

    @Command("${root} allocation list")
    @Permission("echo.command.allocation.list")
    public CompletableFuture<Void> allocationList(CommandContext<S> context) {
        return execute(context, () -> onDemandAdministration().listAllocations(), allocations -> this.format.list(
                "Allocations", allocations.stream().sorted(Comparator.comparing(OnDemandAdministration.Allocation::requestId))
                        .map(allocation -> allocation.requestId() + " -> " + allocation.serverId()).toList()));
    }

    @Command("${root} allocation info <request>")
    @Permission("echo.command.allocation.info")
    public CompletableFuture<Void> allocationInfo(CommandContext<S> context,
                                                  @Argument(value = "request", suggestions = "allocations") String request) {
        return execute(context, () -> onDemandAdministration().getAllocation(request), allocation -> allocation
                .map(this::allocation).orElseGet(() -> this.format.error("Allocation not found: " + request)));
    }

    @Command("${root} allocation acquire <request> <type>")
    @Permission("echo.command.allocation.acquire")
    public CompletableFuture<Void> allocationAcquire(CommandContext<S> context,
                                                     @Argument("request") String request,
                                                     @Argument("type") String type,
                                                     @Flag("confirm") boolean confirm) {
        String command = this.root + " allocation acquire " + request + " " + type + " --confirm";
        return confirm(context, confirm, command, () -> mutate(context, "allocation.acquire", request,
                () -> onDemandServers().acquire(new ServerRequest(request, type)),
                handle -> this.format.success("Allocated server " + handle.id() + "."), ServerHandle::id));
    }

    @Command("${root} allocation reconcile <request>")
    @Permission("echo.command.allocation.reconcile")
    public CompletableFuture<Void> allocationReconcile(CommandContext<S> context,
                                                       @Argument(value = "request", suggestions = "allocations") String request) {
        return mutate(context, "allocation.reconcile", request,
                () -> onDemandAdministration().reconcile(request), reconciliation -> reconciliation
                        .map(this::reconciliation).orElseGet(() -> this.format.error("Allocation not found: " + request)),
                reconciliation -> reconciliation.isPresent() ? "reconciled" : "not_found");
    }

    @Command("${root} allocation terminate <request>")
    @Permission("echo.command.allocation.terminate")
    public CompletableFuture<Void> allocationTerminate(CommandContext<S> context,
                                                       @Argument(value = "request", suggestions = "allocations") String request,
                                                       @Flag("confirm") boolean confirm) {
        return confirm(context, confirm, this.root + " allocation terminate " + request + " --confirm",
                () -> mutate(context, "allocation.terminate", request,
                        () -> onDemandAdministration().terminate(request),
                        terminated -> terminated ? this.format.success("Allocation terminated.")
                                : this.format.warn("Allocation was not found."), String::valueOf));
    }

    @Suggestions("servers")
    public CompletableFuture<List<String>> serverSuggestions() {
        return this.echo.getServers().thenApply(servers -> servers.keySet().stream()
                        .sorted().limit(SUGGESTION_LIMIT).toList())
                .exceptionally(error -> List.of());
    }

    @Suggestions("proxies")
    public CompletableFuture<List<String>> proxySuggestions() {
        return this.echo.getProxies().thenApply(proxies -> proxies.keySet().stream()
                        .sorted().limit(SUGGESTION_LIMIT).toList())
                .exceptionally(error -> List.of());
    }

    @Suggestions("users")
    public CompletableFuture<List<String>> userSuggestions() {
        return this.echo.getAllUsers().thenApply(users -> users.keySet().stream()
                        .map(UUID::toString).sorted().limit(SUGGESTION_LIMIT).toList())
                .exceptionally(error -> List.of());
    }

    @Suggestions("queues")
    public CompletableFuture<List<String>> queueSuggestions() {
        try {
            return queueAdministration().listQueues().thenApply(queues -> queues.stream()
                    .map(queue -> queue.definition().id().value()).sorted().limit(SUGGESTION_LIMIT).toList())
                    .exceptionally(error -> List.of());
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    @Suggestions("placements")
    public CompletableFuture<List<String>> placementSuggestions() {
        try {
            return placement().listActiveReservations().thenApply(values -> values.stream()
                    .map(ServerPlacement.ActiveReservation::requestId).sorted().limit(SUGGESTION_LIMIT).toList())
                    .exceptionally(error -> List.of());
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    @Suggestions("allocations")
    public CompletableFuture<List<String>> allocationSuggestions() {
        try {
            return onDemandAdministration().listAllocations().thenApply(values -> values.stream()
                    .map(OnDemandAdministration.Allocation::requestId).sorted().limit(SUGGESTION_LIMIT).toList())
                    .exceptionally(error -> List.of());
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    private CompletableFuture<NetworkCounts> networkCounts() {
        return this.echo.getServers().thenCombine(this.echo.getProxies(),
                        (servers, proxies) -> new NetworkCounts(servers.size(), proxies.size(), 0))
                .thenCombine(this.echo.getAllUsers(),
                        (counts, users) -> new NetworkCounts(counts.servers(), counts.proxies(), users.size()));
    }

    private CompletableFuture<List<Health>> health() {
        return this.echo.getServers().thenCombine(this.echo.getProxies(), ResourceMaps::new).thenCompose(resources -> {
            List<CompletableFuture<Health>> checks = new ArrayList<>();
            resources.servers().keySet().forEach(id -> checks.add(this.echo.getServerById(id).thenCompose(found -> found
                    .map(server -> server.stillExists().thenApply(alive -> new Health("server", id, alive)))
                    .orElseGet(() -> CompletableFuture.completedFuture(new Health("server", id, false))))));
            resources.proxies().keySet().forEach(id -> checks.add(this.echo.getProxyById(id).thenCompose(found -> found
                    .map(proxy -> proxy.stillExists().thenApply(alive -> new Health("proxy", id, alive)))
                    .orElseGet(() -> CompletableFuture.completedFuture(new Health("proxy", id, false))))));
            return sequence(checks).thenApply(values -> values.stream()
                    .sorted(Comparator.comparing(Health::type).thenComparing(Health::id)).toList());
        });
    }

    private CompletableFuture<Server> requireServer(String id) {
        return this.echo.getServerById(id).thenCompose(found -> found
                .map(CompletableFuture::completedFuture)
                .orElseGet(() -> CompletableFuture.failedFuture(new IllegalArgumentException("Server not found: " + id))));
    }

    private CompletableFuture<Proxy> requireProxy(String id) {
        return this.echo.getProxyById(id).thenCompose(found -> found
                .map(CompletableFuture::completedFuture)
                .orElseGet(() -> CompletableFuture.failedFuture(new IllegalArgumentException("Proxy not found: " + id))));
    }

    private CompletableFuture<User> resolveUser(String input) {
        try {
            return this.echo.getUserById(UUID.fromString(input)).thenCompose(found -> found
                    .map(CompletableFuture::completedFuture)
                    .orElseGet(() -> CompletableFuture.failedFuture(new IllegalArgumentException(
                            "User not found: " + input))));
        } catch (IllegalArgumentException ignored) {
            return this.echo.getUserByUsername(input).thenCompose(found -> found
                    .map(CompletableFuture::completedFuture)
                    .orElseGet(() -> CompletableFuture.failedFuture(new IllegalArgumentException(
                            "User not found: " + input))));
        }
    }

    private CompletableFuture<UserDetails> userDetails(User user) {
        return user.getUsername().thenCombine(user.getCurrentProxyId(), Pair::new)
                .thenCombine(user.getCurrentServerId(), (values, server) -> new UserDetails(user.getId().toString(),
                        values.first().orElse("unknown"), values.second().orElse("none"), server.orElse("none")));
    }

    private CompletableFuture<List<PropertyValue>> properties(PropertyHolder holder) {
        return holder.getPropertiesKeys().thenCompose(keys -> sequence(keys.stream().sorted().map(key ->
                holder.<Object>getProperty(key).thenCombine(holder.getPropertyTimeToLive(key),
                        (value, ttl) -> new PropertyValue(key, value, ttl))).toList()));
    }

    private CompletableFuture<PropertyValue> property(PropertyHolder holder, String key) {
        return holder.<Object>getProperty(key).thenCombine(holder.getPropertyTimeToLive(key),
                (value, ttl) -> new PropertyValue(key, value, ttl));
    }

    private CompletableFuture<ResourceControlRequest.Response> control(
            EchoResourceType type, String id, ResourceControlRequest.Action action) {
        return this.remote.control(new ResourceControlRequest(action, type, id, null), CONTROL_TIMEOUT);
    }

    private CompletableFuture<ResourceControlRequest.Response> drain(EchoResourceType type, String id, int minutes) {
        Duration deadline = positiveMinutes(minutes);
        return this.remote.control(new ResourceControlRequest(ResourceControlRequest.Action.DRAIN,
                type, id, deadline, null), CONTROL_TIMEOUT);
    }

    private CompletableFuture<Void> retry(CommandContext<S> context, String action, String queue, String request) {
        return mutate(context, action, queue + "/" + request, () -> {
            QueueService service = queueService();
            QueueId queueId = new QueueId(queue);
            return service.get(queueId, request).thenCompose(found -> {
                if (found.isEmpty())
                    return CompletableFuture.completedFuture(new RetryResult(null, false));
                QueueRequestStatus.State state = found.get().state();
                if (state != QueueRequestStatus.State.FAILED && state != QueueRequestStatus.State.CANCELLED)
                    return CompletableFuture.completedFuture(new RetryResult(state, false));
                return service.administration().retry(queueId, request)
                        .thenApply(retried -> new RetryResult(state, retried));
            });
        }, result -> {
            if (result.state() == null)
                return this.format.error("Queue ticket not found: " + request);
            if (result.state() != QueueRequestStatus.State.FAILED
                    && result.state() != QueueRequestStatus.State.CANCELLED)
                return this.format.error("Retry is allowed only for FAILED/CANCELLED; current state is "
                        + result.state() + ".");
            return result.retried() ? this.format.success("Queue request retried.")
                    : this.format.warn("Queue request was not retried.");
        }, result -> result.retried() ? "retried" : "rejected");
    }

    private QueueService queueService() {
        try {
            return this.queueLoader.get();
        } catch (IllegalStateException error) {
            throw new IllegalStateException("Queue service is not loaded in this process.");
        }
    }

    private QueueAdministration queueAdministration() {
        return queueService().administration();
    }

    private OnDemandServers onDemandServers() {
        try {
            return this.onDemandLoader.get();
        } catch (IllegalStateException error) {
            throw new IllegalStateException("On-demand servers are not loaded in this process.");
        }
    }

    private OnDemandAdministration onDemandAdministration() {
        return onDemandServers().administration();
    }

    private ServerPlacement placement() {
        return this.echo.getServerPlacement();
    }

    private ServerPlacement.Request placementRequest(String request, String serverType, Set<UUID> members,
                                                     ServerPlacement.Policy policy, Duration lease) {
        return new ServerPlacement.Request(request, members, Set.of(),
                Map.of(PROPERTY_SERVER_TYPE, serverType), policy, lease);
    }

    private Component resourceInfo(ResourceInfo info) {
        return this.format.rows("Resource", Map.of("id", info.id(), "address", info.address(),
                "users", info.users(), "availability", info.availability()));
    }

    private Component propertyList(String title, List<PropertyValue> values) {
        return this.format.list(title, values.stream().map(value -> value.key() + "="
                + value.value().map(String::valueOf).orElse("<missing>") + " ttl="
                + this.format.duration(value.ttl())).toList());
    }

    private Component propertyValue(String title, PropertyValue value) {
        if (value.value().isEmpty())
            return this.format.error("Property not found: " + value.key());
        return this.format.rows(title, Map.of("key", value.key(), "value", value.value().get(),
                "ttl", this.format.duration(value.ttl())));
    }

    private Component serverLoadResult(Optional<ServerLoadSnapshot> snapshot) {
        if (snapshot.isEmpty())
            return this.format.warn("No server load has been published.");
        ServerLoadSnapshot value = snapshot.get();
        return this.format.rows("Server load", Map.of(
                "participants", value.load().participantCount(),
                "accepting queue assignments", value.load().acceptingQueueAssignments(),
                "sampled", this.format.instant(value.sampledAt()),
                "valid until", this.format.instant(value.validUntil()),
                "stale", value.isStale(Instant.now())));
    }

    private Component controlResult(ResourceControlRequest.Response response) {
        if (response.isAccepted() && response.getStatus() == ResourceControlRequest.Status.ACCEPTED)
            return this.format.success(response.getMessage());
        if (response.isAccepted())
            return this.format.error(unexpectedFailure(new IllegalStateException(
                    "Remote resource control returned " + response.getStatus() + " with acceptance")));
        String message = switch (response.getStatus()) {
            case INVALID_REQUEST -> "Remote request was invalid.";
            case WRONG_TARGET -> "Remote request reached the wrong target.";
            case EXPIRED, TIMED_OUT -> "Remote request timed out.";
            case NOT_ALLOWED -> "Remote action was not allowed.";
            case UNSUPPORTED_ACTION -> "Remote action is not supported.";
            case FAILED -> unexpectedFailure(new IllegalStateException(
                    "Remote resource control failed: " + response.getMessage()));
            case ACCEPTED -> unexpectedFailure(new IllegalStateException(
                    "Remote resource control returned accepted status without acceptance"));
        };
        return this.format.error(message);
    }

    private Component disconnectResult(UserDisconnectRequest.Response response) {
        if (response.isAccepted() && response.getStatus() == UserDisconnectRequest.Status.DISCONNECTED)
            return this.format.success("User disconnected.");
        if (response.isAccepted())
            return this.format.error(unexpectedFailure(new IllegalStateException(
                    "Remote disconnect returned " + response.getStatus() + " with acceptance")));
        String message = switch (response.getStatus()) {
            case PLAYER_NOT_FOUND -> "Player was not found.";
            case INVALID_REQUEST -> "Remote disconnect request was invalid.";
            case WRONG_TARGET -> "Remote disconnect request reached the wrong target.";
            case EXPIRED, TIMED_OUT -> "Remote disconnect request timed out.";
            case FAILED -> unexpectedFailure(new IllegalStateException(
                    "Remote user disconnect failed: " + response.getMessage()));
            case DISCONNECTED -> unexpectedFailure(new IllegalStateException(
                    "Remote disconnect returned disconnected status without acceptance"));
        };
        return this.format.error(message);
    }

    private Component queueOverviewList(List<QueueAdministration.QueueOverview> queues) {
        return this.format.list("Queues", queues.stream()
                .sorted(Comparator.comparing(queue -> queue.definition().id().value()))
                .map(queue -> queue.definition().id() + " " + (queue.paused() ? "paused" : "active")
                        + " " + queue.counts()).toList());
    }

    private Component queueOverview(QueueAdministration.QueueOverview queue) {
        return this.format.rows("Queue", Map.of(
                "id", queue.definition().id(), "server type", queue.definition().serverType(),
                "policy", queue.definition().placementPolicy(), "paused", queue.paused(),
                "reason", Optional.ofNullable(queue.pauseReason()).orElse("none"),
                "counts", queue.counts(), "server", Optional.ofNullable(queue.serverId()).orElse("none")));
    }

    private String ticketLine(QueueAdministration.QueueTicket ticket) {
        return ticket.status().request().requestId() + " " + ticket.status().state()
                + " created=" + Optional.ofNullable(ticket.createdAt()).map(this.format::instant).orElse("unknown")
                + " updated=" + Optional.ofNullable(ticket.updatedAt()).map(this.format::instant).orElse("unknown");
    }

    private Component queueStatus(QueueRequestStatus status) {
        return this.format.rows("Queue ticket", Map.of(
                "request", status.request().requestId(), "queue", status.request().queueId(),
                "state", status.state(), "version", status.version(),
                "members", status.request().members().stream().map(UUID::toString).sorted().toList(),
                "server", Optional.ofNullable(status.serverId()).orElse("none"),
                "failure", Optional.ofNullable(status.failure()).orElse("none")));
    }

    private Component placementStatus(ServerPlacement.ServerStatus status) {
        return this.format.rows("Placement server", Map.of(
                "server", status.serverId(), "heartbeat", status.heartbeatAlive(),
                "availability", status.availability(),
                "participants", status.participantLoad().isPresent()
                        ? status.participantLoad().getAsInt() : "unknown",
                "reserved", status.reservedSlots(),
                "capacity", status.capacity().isPresent() ? status.capacity().getAsInt() : "unknown",
                "free", status.freeSlots().isPresent() ? status.freeSlots().getAsLong() : "unknown",
                "load fresh", status.loadFresh(), "accepting queue", status.acceptingQueueAssignments()));
    }

    private Component placementExplanation(ServerPlacement.Explanation explanation) {
        List<String> lines = new ArrayList<>();
        lines.add("selected: " + explanation.selectedServerId().orElse("none"));
        explanation.candidates().forEach(candidate -> lines.add(candidate.serverId() + ": "
                + candidate.rejectionReason() + " effectiveLoad="
                + (candidate.effectiveLoad().isPresent() ? candidate.effectiveLoad().getAsLong() : "unknown")));
        return this.format.list("Placement explanation", lines);
    }

    private Component reservationList(List<ServerPlacement.ActiveReservation> reservations) {
        return this.format.list("Placement reservations", reservations.stream()
                .sorted(Comparator.comparing(ServerPlacement.ActiveReservation::requestId))
                .map(value -> value.requestId() + " -> " + value.serverId() + " members="
                        + value.members().size() + " expires=" + this.format.instant(value.expiresAt())).toList());
    }

    private Component reservation(ServerPlacement.Reservation reservation) {
        return this.format.rows("Placement reservation", Map.of(
                "request", reservation.requestId(), "server", reservation.serverId(),
                "members", reservation.members().stream().map(UUID::toString).sorted().toList(),
                "expires", this.format.instant(reservation.expiresAt())));
    }

    private Component reservation(ServerPlacement.ActiveReservation reservation) {
        return this.format.rows("Placement reservation", Map.of(
                "request", reservation.requestId(), "server", reservation.serverId(),
                "members", reservation.members().stream().map(UUID::toString).sorted().toList(),
                "expires", this.format.instant(reservation.expiresAt())));
    }

    private Component allocation(OnDemandAdministration.Allocation allocation) {
        return this.format.rows("Allocation", Map.of(
                "request", allocation.requestId(), "server", allocation.serverId()));
    }

    private Component reconciliation(OnDemandAdministration.Reconciliation reconciliation) {
        return this.format.rows("Allocation reconciliation", Map.of(
                "request", reconciliation.allocation().requestId(),
                "server", reconciliation.allocation().serverId(), "live", reconciliation.live(),
                "availability", Optional.ofNullable(reconciliation.availability()).map(Object::toString).orElse("none")));
    }

    private CompletableFuture<Void> confirm(CommandContext<S> context, boolean confirmed, String command,
                                            Supplier<CompletableFuture<Void>> operation) {
        if (!confirmed)
            return send(context, this.format.warn("Run: " + command));
        try {
            return operation.get();
        } catch (Throwable error) {
            return failed(context, error);
        }
    }

    private <T> CompletableFuture<Void> execute(CommandContext<S> context,
                                                Supplier<? extends CompletableFuture<T>> operation,
                                                Function<T, Component> result) {
        final CompletableFuture<T> future;
        try {
            future = Objects.requireNonNull(operation.get(), "operation future");
        } catch (Throwable error) {
            return failed(context, error);
        }
        return future.handle((value, error) -> {
            Component message;
            try {
                message = error == null ? result.apply(value) : this.format.error(errorMessage(error));
            } catch (Throwable failure) {
                message = this.format.error(errorMessage(failure));
            }
            this.audience.send(context.sender(), message);
            return null;
        });
    }

    private <T> CompletableFuture<Void> mutate(CommandContext<S> context, String action, String target,
                                               Supplier<? extends CompletableFuture<T>> operation,
                                               Function<T, Component> result, Function<T, String> outcome) {
        final CompletableFuture<T> future;
        try {
            future = Objects.requireNonNull(operation.get(), "operation future");
        } catch (Throwable error) {
            audit(context, action, target, "failed:" + unwrap(error).getClass().getSimpleName());
            return failed(context, error);
        }
        return future.handle((value, error) -> {
            if (error != null) {
                audit(context, action, target, "failed:" + unwrap(error).getClass().getSimpleName());
                this.audience.send(context.sender(), this.format.error(errorMessage(error)));
            } else {
                try {
                    String auditOutcome = outcome.apply(value);
                    Component message = result.apply(value);
                    audit(context, action, target, auditOutcome);
                    this.audience.send(context.sender(), message);
                } catch (Throwable failure) {
                    audit(context, action, target, "failed:" + unwrap(failure).getClass().getSimpleName());
                    this.audience.send(context.sender(), this.format.error(errorMessage(failure)));
                }
            }
            return null;
        });
    }

    private CompletableFuture<Void> failed(CommandContext<S> context, Throwable error) {
        return send(context, this.format.error(errorMessage(error)));
    }

    private CompletableFuture<Void> send(CommandContext<S> context, Component message) {
        this.audience.send(context.sender(), message);
        return CompletableFuture.completedFuture(null);
    }

    private void audit(CommandContext<S> context, String action, String target, String outcome) {
        this.logger.info(() -> "echo_audit sender=" + auditField(this.audience.identity(context.sender()))
                + " action=" + auditField(action) + " target=" + auditField(target)
                + " outcome=" + auditField(outcome));
    }

    private String errorMessage(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof TimeoutException)
            return "Operation timed out.";
        if (isSafeArgumentFailure(cause)
                || cause instanceof UnknownResourceException
                || cause instanceof UserHasNoProxyException
                || isSafeStateFailure(cause))
            return cause.getMessage() == null || cause.getMessage().isBlank()
                    ? cause.getClass().getSimpleName() : cause.getMessage();
        return unexpectedFailure(cause);
    }

    private String unexpectedFailure(Throwable cause) {
        UUID correlationId = UUID.randomUUID();
        this.logger.log(Level.SEVERE, "echo_command_failure correlationId=" + correlationId, cause);
        return "Command failed. Reference: " + correlationId;
    }

    private static boolean isSafeStateFailure(Throwable cause) {
        if (!(cause instanceof IllegalStateException) || cause.getMessage() == null)
            return false;
        return cause.getMessage().equals("Queue service is not loaded in this process.")
                || cause.getMessage().equals("On-demand servers are not loaded in this process.")
                || cause.getMessage().equals("A member already has an active request in this queue")
                || cause.getMessage().startsWith("Queue request ID has a different payload: ");
    }

    private static boolean isSafeArgumentFailure(Throwable cause) {
        if (!(cause instanceof IllegalArgumentException) || cause.getMessage() == null)
            return false;
        String message = cause.getMessage();
        return message.equals("members must contain at least one UUID")
                || message.equals("minutes must be positive")
                || message.equals("leaseSeconds must be positive")
                || message.equals("requestId must not be blank")
                || message.equals("members must not be empty")
                || message.equals("type must not be blank")
                || message.equals("Queue ID must not be blank")
                || message.startsWith("Invalid UUID string")
                || message.startsWith("Unknown placement policy: ")
                || message.startsWith("Unknown queue: ")
                || message.startsWith("Queue not found: ")
                || message.startsWith("Reservation is not held by this process: ")
                || message.startsWith("Server not found: ")
                || message.startsWith("Proxy not found: ")
                || message.startsWith("User not found: ");
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null)
            current = current.getCause();
        return current;
    }

    private static String auditField(String value) {
        return String.valueOf(value).replaceAll("\\s+", "_");
    }

    private static CompletableFuture<String> address(Joinable resource) {
        return CompletableFuture.supplyAsync(() -> {
            Address address = resource.getAddress();
            return address.getHost() + ":" + address.getPort();
        }, VIRTUAL_THREADS);
    }

    private static Set<UUID> parseMembers(String input) {
        Set<UUID> members = java.util.Arrays.stream(input.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
        if (members.isEmpty())
            throw new IllegalArgumentException("members must contain at least one UUID");
        return members;
    }

    private static ServerPlacement.Policy policy(String value) {
        try {
            return ServerPlacement.Policy.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown placement policy: " + value);
        }
    }

    private static Duration positiveMinutes(long minutes) {
        if (minutes <= 0)
            throw new IllegalArgumentException("minutes must be positive");
        return Duration.ofMinutes(minutes);
    }

    private static Duration positiveSeconds(long seconds) {
        if (seconds <= 0)
            throw new IllegalArgumentException("leaseSeconds must be positive");
        return Duration.ofSeconds(seconds);
    }

    private static String explainRequestId(String serverType, Set<UUID> members, ServerPlacement.Policy policy) {
        String seed = serverType + '|' + members.stream().map(UUID::toString).sorted()
                .collect(Collectors.joining(",")) + '|' + policy;
        return "explain-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static String quote(String value) {
        if (!value.chars().anyMatch(Character::isWhitespace))
            return value;
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String requireRoot(String root) {
        String value = Objects.requireNonNull(root, "rootSyntax");
        if (!value.matches("[A-Za-z0-9_/-]+(?:\\|[A-Za-z0-9_/-]+)*"))
            throw new IllegalArgumentException("rootSyntax must contain command literals separated by |");
        return value;
    }

    private static <T> CompletableFuture<List<T>> sequence(List<CompletableFuture<T>> futures) {
        CompletableFuture<?>[] array = futures.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(array).thenApply(ignored -> futures.stream()
                .map(future -> future.getNow(null)).toList());
    }

    private record NetworkCounts(int servers, int proxies, int users) {}
    private record ResourceMaps(Map<String, Long> servers, Map<String, Long> proxies) {}
    private record Health(String type, String id, boolean alive) {}
    private record ResourceInfo(String id, String address, int users, String availability) {}
    private record UserDetails(String id, String username, String proxy, String server) {}
    private record Pair(Optional<String> first, Optional<String> second) {}
    private record PropertyValue(String key, Optional<Object> value, long ttl) {}
    private record RetryResult(QueueRequestStatus.State state, boolean retried) {}
}
