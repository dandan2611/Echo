package fr.codinbox.echo.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("unit")
class ClientBrandTest {

    @ParameterizedTest
    @CsvSource({
        "vanilla, vanilla",
        "fabric, fabric",
        "Forge, forge",
        "neoforge, neoforge",
        "lunarclient:v2.18.3-2451, lunarclient",
        "Feather Fabric, feather",
        "LabyMod 4, labymod",
        "my-custom-client, other",
        "'', unknown"
    })
    void normalizesBrands(final @NotNull String brand, final @NotNull String expected) {
        assertThat(ClientBrand.normalize(brand)).isEqualTo(expected);
    }
}
