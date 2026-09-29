package fr.codinbox.echo.commands;

import fr.codinbox.echo.api.EchoClient;
import fr.codinbox.echo.api.user.User;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/** Read-only view of a network membership snapshot. */
final class NetworkUserList {
    private static final int PAGE_SIZE = 20;
    private static final int READ_CONCURRENCY = 32;
    private static final long READ_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private final EchoClient echo;

    NetworkUserList(EchoClient echo) {
        this.echo = echo;
    }

    CompletableFuture<Component> render(Set<UUID> members, Group grouping, boolean names, int page, String command) {
        if (!names && grouping == Group.NONE)
            return CompletableFuture.completedFuture(format(
                    members.stream()
                            .map(id -> new Entry(id, id.toString(), "", false))
                            .toList(),
                    grouping,
                    false,
                    page,
                    command));
        List<UUID> ids = List.copyOf(members);
        long deadline = System.nanoTime() + READ_TIMEOUT_NANOS;
        CompletableFuture<List<Entry>> result = CompletableFuture.completedFuture(new ArrayList<>(ids.size()));
        // Batches bound Redis fan-out. A shared deadline also bounds a fully stalled network:
        // later batches fall back without issuing fresh requests after the deadline.
        for (int start = 0; start < ids.size(); start += READ_CONCURRENCY) {
            List<UUID> batch = ids.subList(start, Math.min(ids.size(), start + READ_CONCURRENCY));
            result = result.thenComposeAsync(entries -> {
                List<CompletableFuture<Entry>> reads = batch.stream()
                        .map(id -> readEntry(id, grouping, names, deadline))
                        .toList();
                return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new))
                        .thenApply(ignored -> {
                            reads.forEach(read -> entries.add(read.join()));
                            return entries;
                        });
            });
        }
        return result.thenApplyAsync(entries -> format(entries, grouping, names, page, command));
    }

    private CompletableFuture<Entry> readEntry(UUID id, Group grouping, boolean names, long deadline) {
        return read(() -> this.echo.getUserById(id), deadline)
                .thenCompose(found -> {
                    if (found.isEmpty()) return CompletableFuture.completedFuture(unavailable(id, grouping));
                    User user = found.get();
                    CompletableFuture<Field> group = switch (grouping) {
                        case SERVER -> field(user::getCurrentServerId, deadline);
                        case PROXY -> field(user::getCurrentProxyId, deadline);
                        case NONE -> CompletableFuture.completedFuture(new Field(Optional.of(""), false));
                    };
                    CompletableFuture<Field> name = names
                            ? field(user::getUsername, deadline)
                            : CompletableFuture.completedFuture(new Field(Optional.empty(), false));
                    return group.thenCombine(
                            name,
                            (location, username) -> new Entry(
                                    id,
                                    username.value().orElse(id.toString()),
                                    location.unavailable()
                                            ? unknownGroup(grouping)
                                            : location.value()
                                                    .orElse(grouping == Group.SERVER ? "(no server)" : "(no proxy)"),
                                    location.unavailable()
                                            || (names && username.value().isEmpty())));
                })
                .exceptionally(error -> unavailable(id, grouping));
    }

    private CompletableFuture<Field> field(
            Supplier<? extends CompletableFuture<Optional<String>>> lookup, long deadline) {
        return read(lookup, deadline)
                .handle((value, error) -> new Field(error == null ? value : Optional.empty(), error != null));
    }

    private <T> CompletableFuture<T> read(Supplier<? extends CompletableFuture<T>> lookup, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return CompletableFuture.failedFuture(new TimeoutException());
        try {
            // Never time out a shared API future in place.
            return lookup.get().copy().orTimeout(remaining, TimeUnit.NANOSECONDS);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private Entry unavailable(UUID id, Group grouping) {
        return new Entry(id, id.toString(), unknownGroup(grouping), true);
    }

    private String unknownGroup(Group grouping) {
        return switch (grouping) {
            case SERVER -> "(unknown server)";
            case PROXY -> "(unknown proxy)";
            case NONE -> "";
        };
    }

    private Component format(List<Entry> entries, Group grouping, boolean names, int page, String command) {
        List<Entry> sorted = entries.stream()
                .sorted(Comparator.comparing(Entry::group)
                        .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Entry::id))
                .toList();
        Map<String, List<Entry>> groups =
                sorted.stream().collect(Collectors.groupingBy(Entry::group, TreeMap::new, Collectors.toList()));
        int size = names ? sorted.size() : grouping == Group.NONE ? 0 : groups.size();
        int pages = Math.max(1, (int) Math.ceil(size / (double) PAGE_SIZE));
        if (page > pages)
            throw new IllegalArgumentException("Glist: page " + page + " is out of range (1-" + pages + ").");
        long offset = (long) (page - 1) * PAGE_SIZE;
        List<String> lines = new ArrayList<>();
        if (names) {
            sorted.stream()
                    .skip(offset)
                    .limit(PAGE_SIZE)
                    .collect(Collectors.groupingBy(Entry::group, TreeMap::new, Collectors.toList()))
                    .forEach((group, users) -> lines.add((grouping == Group.NONE
                                    ? ""
                                    : group + " (" + groups.get(group).size() + "): ")
                            + users.stream().map(Entry::name).collect(Collectors.joining(", "))));
        } else if (grouping != Group.NONE) {
            groups.entrySet().stream()
                    .skip(offset)
                    .limit(PAGE_SIZE)
                    .forEach(entry ->
                            lines.add(entry.getKey() + " (" + entry.getValue().size() + ")"));
        }
        Component result = Component.text(
                "Users — Total: " + entries.size() + " — Page " + page + "/" + pages, NamedTextColor.AQUA);
        for (String line : lines)
            result = result.append(Component.newline()).append(Component.text(line, NamedTextColor.WHITE));
        if (entries.isEmpty())
            result = result.append(Component.newline()).append(Component.text("No users matched the selection."));
        long unavailable = entries.stream().filter(Entry::partial).count();
        if (unavailable > 0)
            result = result.append(Component.newline())
                    .append(Component.text(
                            "Partial results: details unavailable for " + unavailable + " user(s).",
                            NamedTextColor.YELLOW));
        if (page > 1) result = result.append(Component.newline()).append(pageLink("Previous", command, page - 1));
        if (page < pages) result = result.append(Component.newline()).append(pageLink("Next", command, page + 1));
        return result;
    }

    private Component pageLink(String label, String command, int page) {
        String input = command + " --page " + page;
        return Component.text("[" + label + "]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand(input))
                .hoverEvent(Component.text(input));
    }

    enum Group {
        SERVER,
        PROXY,
        NONE;

        static Group parse(String value) {
            if (value == null) return SERVER;
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Glist: --group must be server, proxy or none.");
            }
        }
    }

    private record Entry(UUID id, String name, String group, boolean partial) {}

    private record Field(Optional<String> value, boolean unavailable) {}
}
