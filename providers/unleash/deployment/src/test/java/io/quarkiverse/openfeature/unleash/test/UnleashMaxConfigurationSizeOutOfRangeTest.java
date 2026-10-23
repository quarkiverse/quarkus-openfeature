package io.quarkiverse.openfeature.unleash.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/**
 * A maximum response size larger than a single buffer can hold must fail the startup, not
 * throw on the event loop where nothing would see it.
 */
public class UnleashMaxConfigurationSizeOutOfRangeTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.unleash.url", "http://unleash.example.com:4242/api")
            .overrideConfigKey("quarkus.openfeature.unleash.max-configuration-size", "5G")
            .overrideConfigKey("quarkus.devservices.enabled", "false")
            .assertException(e -> {
                assertThat(e).hasMessageContaining("max-configuration-size")
                        .hasMessageContaining("out of range");
            });

    @Test
    void trigger() {
    }
}
