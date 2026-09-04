package fr.codinbox.echo.commands;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.EchoFuture;
import fr.codinbox.echo.api.messaging.MessagingProvider;
import fr.codinbox.echo.api.messaging.impl.ResourceControlRequest;
import fr.codinbox.echo.api.messaging.impl.UserDisconnectRequest;
import fr.codinbox.echo.api.server.placement.ServerPlacement;
import fr.codinbox.echo.ondemand.OnDemandAdministration;
import fr.codinbox.echo.ondemand.OnDemandServers;
import fr.codinbox.echo.queue.QueueAdministration;
import fr.codinbox.echo.queue.QueueService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.incendo.cloud.context.CommandContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class CommandTestSupport {

    private CommandTestSupport() {
    }

    static Fixture fixture() {
        return fixture(() -> mock(QueueService.class), () -> mock(OnDemandServers.class));
    }

    static Fixture fixture(Supplier<QueueService> queueLoader, Supplier<OnDemandServers> onDemandLoader) {
        EchoClient echo = mock(EchoClient.class);
        QueueService queues = queueLoader.get();
        OnDemandServers onDemand = onDemandLoader.get();
        QueueAdministration queueAdministration = mock(QueueAdministration.class);
        OnDemandAdministration onDemandAdministration = mock(OnDemandAdministration.class);
        ServerPlacement placement = mock(ServerPlacement.class);
        MessagingProvider messaging = mock(MessagingProvider.class);
        Logger logger = logger();
        FakeAudience audience = new FakeAudience();

        when(queues.administration()).thenReturn(queueAdministration);
        when(onDemand.administration()).thenReturn(onDemandAdministration);
        when(echo.getServerPlacement()).thenReturn(placement);
        when(echo.getMessagingProvider()).thenReturn(messaging);
        when(echo.getLocalTopic()).thenReturn("echo:test");

        @SuppressWarnings("unchecked")
        CommandContext<String> context = mock(CommandContext.class);
        when(context.sender()).thenReturn("console user");
        EchoCommands<String> commands = new EchoCommands<>(echo, audience, "echo|echoserver",
                () -> queues, () -> onDemand, logger);
        return new Fixture(commands, context, audience, echo, queues, queueAdministration,
                onDemand, onDemandAdministration, placement, messaging, logger);
    }

    static Fixture fixtureWithFailingQueueLoader() {
        EchoClient echo = mock(EchoClient.class);
        OnDemandServers onDemand = mock(OnDemandServers.class);
        OnDemandAdministration administration = mock(OnDemandAdministration.class);
        Logger logger = logger();
        FakeAudience audience = new FakeAudience();
        when(onDemand.administration()).thenReturn(administration);
        @SuppressWarnings("unchecked")
        CommandContext<String> context = mock(CommandContext.class);
        when(context.sender()).thenReturn("console user");
        EchoCommands<String> commands = new EchoCommands<>(echo, audience, "echo",
                () -> { throw new IllegalStateException("not loaded"); }, () -> onDemand, logger);
        return new Fixture(commands, context, audience, echo, null, null,
                onDemand, administration, null, null, logger);
    }

    static Fixture fixtureWithFailingOnDemandLoader() {
        EchoClient echo = mock(EchoClient.class);
        QueueService queues = mock(QueueService.class);
        QueueAdministration administration = mock(QueueAdministration.class);
        Logger logger = logger();
        FakeAudience audience = new FakeAudience();
        when(queues.administration()).thenReturn(administration);
        @SuppressWarnings("unchecked")
        CommandContext<String> context = mock(CommandContext.class);
        when(context.sender()).thenReturn("console user");
        EchoCommands<String> commands = new EchoCommands<>(echo, audience, "echo",
                () -> queues, () -> { throw new IllegalStateException("not loaded"); }, logger);
        return new Fixture(commands, context, audience, echo, queues, administration,
                null, null, null, null, logger);
    }

    static void controlResponse(Fixture fixture, boolean accepted, ResourceControlRequest.Status status,
                                String message) {
        when(fixture.messaging.request(anyString(), any(ResourceControlRequest.class),
                eq(ResourceControlRequest.Response.class), any(Duration.class))).thenAnswer(invocation -> {
            ResourceControlRequest request = invocation.getArgument(1);
            return EchoFuture.completed(new ResourceControlRequest.Response(request, accepted, status, message));
        });
    }

    static void disconnectResponse(Fixture fixture, boolean accepted, UserDisconnectRequest.Status status,
                                   String message) {
        when(fixture.messaging.request(anyString(), any(UserDisconnectRequest.class),
                eq(UserDisconnectRequest.Response.class), any(Duration.class))).thenAnswer(invocation -> {
            UserDisconnectRequest request = invocation.getArgument(1);
            return EchoFuture.completed(new UserDisconnectRequest.Response(request, accepted, status, message));
        });
    }

    static <T> EchoFuture<T> echoFailed(Throwable error) {
        EchoFuture<T> future = new EchoFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    static <T> CompletableFuture<T> failed(Throwable error) {
        return CompletableFuture.failedFuture(error);
    }

    private static Logger logger() {
        Logger logger = mock(Logger.class);
        doAnswer(invocation -> {
            invocation.<Supplier<String>>getArgument(0).get();
            return null;
        }).when(logger).info(any(Supplier.class));
        return logger;
    }

    record Fixture(EchoCommands<String> commands, CommandContext<String> context, FakeAudience audience,
                   EchoClient echo, QueueService queues, QueueAdministration queueAdministration,
                   OnDemandServers onDemand, OnDemandAdministration onDemandAdministration,
                   ServerPlacement placement, MessagingProvider messaging, Logger logger) {

        String output() {
            return this.audience.output();
        }

        void clearOutput() {
            this.audience.messages.clear();
        }
    }

    static final class FakeAudience implements CommandAudience<String> {
        private final List<Component> messages = new ArrayList<>();

        @Override
        public void send(String sender, Component message) {
            this.messages.add(message);
        }

        @Override
        public String identity(String sender) {
            return "audit " + sender;
        }

        private String output() {
            return this.messages.stream().map(PlainTextComponentSerializer.plainText()::serialize)
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
    }
}
