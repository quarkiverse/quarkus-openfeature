package io.quarkiverse.openfeature.flagd.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkiverse.openfeature.flagd.deployment.FlagdBuildTimeConfig;
import io.quarkiverse.openfeature.runtime.OpenFeatureBuildTimeConfig;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * The Dev Services processors key the service configuration on a hash code
 * of {@code a DevServicesConfig}, which is only correct if a value-based
 * implementation of {@code hashCode} (and {@code equals}) are generated
 * for the config interface implementation. This test verifies that it is.
 * <p>
 * All four providers do this and all four {@code DevServicesConfig} interfaces
 * have the same shape, so flagd stands for all of them. What is under test
 * here is SmallRye Config's code generation, not anything specific to flagd.
 */
public class FlagdDevServicesConfigHashCodeTest {
    private static FlagdBuildTimeConfig.DevServicesConfig devServices(Map<String, String> properties) {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withMapping(FlagdBuildTimeConfig.class)
                .withSources(new PropertiesConfigSource(properties, "test", 100))
                .build();
        return config.getConfigMapping(FlagdBuildTimeConfig.class)
                .domains()
                .get(OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN)
                .flagd()
                .devservices();
    }

    @Test
    public void sameConfigurationHasSameHashCode() {
        Map<String, String> properties = Map.of(
                "quarkus.openfeature.flagd.devservices.image-name", "ghcr.io/open-feature/flagd:v0.11.1",
                "quarkus.openfeature.flagd.devservices.port", "8013",
                "quarkus.openfeature.flagd.devservices.service-name", "my-flagd");

        FlagdBuildTimeConfig.DevServicesConfig first = devServices(properties);
        FlagdBuildTimeConfig.DevServicesConfig second = devServices(properties);

        assertNotSame(first, second);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void differentConfigurationHasDifferentHashCode() {
        FlagdBuildTimeConfig.DevServicesConfig first = devServices(
                Map.of("quarkus.openfeature.flagd.devservices.port", "8013"));
        FlagdBuildTimeConfig.DevServicesConfig second = devServices(
                Map.of("quarkus.openfeature.flagd.devservices.port", "8014"));

        assertNotEquals(first, second);
        assertNotEquals(first.hashCode(), second.hashCode());
    }
}
