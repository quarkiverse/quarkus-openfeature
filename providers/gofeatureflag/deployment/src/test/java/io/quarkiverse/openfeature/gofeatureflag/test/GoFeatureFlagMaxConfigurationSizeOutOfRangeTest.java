package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/**
 * A maximum configuration size larger than a single buffer can hold must fail the startup, not
 * throw on the event loop where nothing would see it.
 */
public class GoFeatureFlagMaxConfigurationSizeOutOfRangeTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.gofeatureflag.url", "http://gofeatureflag.example.com:1031")
            .overrideConfigKey("quarkus.openfeature.gofeatureflag.max-configuration-size", "5G")
            .overrideConfigKey("quarkus.devservices.enabled", "false")
            .assertException(e -> {
                assertThat(e).hasMessageContaining("max-configuration-size")
                        .hasMessageContaining("out of range");
            });

    @Test
    void trigger() {
    }
}
