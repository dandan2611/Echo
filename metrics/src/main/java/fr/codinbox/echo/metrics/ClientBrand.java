package fr.codinbox.echo.metrics;

import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Maps the free-form client brand sent by players to a bounded set of label values. */
public final class ClientBrand {

    /** Brand label when the client has not sent one yet. */
    public static final @NotNull String UNKNOWN = "unknown";

    /** Brand label for any unrecognized brand. */
    public static final @NotNull String OTHER = "other";

    // Ordered: launchers wrapping a mod loader come before the loaders themselves.
    private static final @NotNull List<String> KNOWN = List.of(
            "lunarclient",
            "badlion",
            "feather",
            "labymod",
            "essential",
            "optifine",
            "neoforge",
            "forge",
            "quilt",
            "fabric",
            "vanilla");

    private ClientBrand() {}

    public static @NotNull String normalize(final @Nullable String brand) {
        if (brand == null || brand.isBlank()) return UNKNOWN;
        final String lower = brand.toLowerCase(Locale.ROOT).replace(" ", "");
        for (final String known : KNOWN) if (lower.contains(known)) return known;
        if (lower.contains("lunar")) return "lunarclient";
        return OTHER;
    }
}
