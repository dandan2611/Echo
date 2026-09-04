package fr.codinbox.echo.api.utils;

import fr.codinbox.echo.api.property.PropertyKey;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class EnvUtilsTest {

    @Test
    void getInitialProperties_extractsOnlyPrefixedVariables() {
        Map<String, String> environment = Map.of(
                "ECHO_RESOURCE_PROPERTY_server_type", "lobby",
                "ECHO_RESOURCE_PROPERTY_region", "eu-west",
                "ECHO_RESOURCE_ID", "lobby-1");

        assertThat(EnvUtils.getInitialProperties(environment)).containsOnly(
                Map.entry(new PropertyKey<>("server_type"), "lobby"),
                Map.entry(new PropertyKey<>("region"), "eu-west"));
    }

    @Test
    void getInitialProperties_preservesKeyCaseAndSnapshotsResult() {
        Map<String, String> environment = new HashMap<>();
        environment.put("ECHO_RESOURCE_PROPERTY_serverTypes", "lobby");

        Map<PropertyKey<String>, String> properties = EnvUtils.getInitialProperties(environment);
        environment.put("ECHO_RESOURCE_PROPERTY_region", "eu-west");

        assertThat(properties).isEqualTo(Map.of(new PropertyKey<String>("serverTypes"), "lobby"));
        assertThatThrownBy(properties::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getInitialProperties_rejectsEmptyPropertyKey() {
        assertThatThrownBy(() -> EnvUtils.getInitialProperties(Map.of(
                "ECHO_RESOURCE_PROPERTY_", "value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("property key");
    }
}
