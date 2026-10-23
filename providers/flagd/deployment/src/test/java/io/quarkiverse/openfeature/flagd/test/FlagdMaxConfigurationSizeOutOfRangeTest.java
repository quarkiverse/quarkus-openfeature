package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/**
 * A maximum flag configuration size that Vert.x cannot accept must fail the startup, not
 * throw on the event loop where nothing would see it.
 */
public class FlagdMaxConfigurationSizeOutOfRangeTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.flagd.url", "flagd.example.com:8015")
            .overrideConfigKey("quarkus.openfeature.flagd.max-configuration-size", "5G")
            .overrideConfigKey("quarkus.devservices.enabled", "false")
            .assertException(e -> {
                assertThat(e).hasMessageContaining("max-configuration-size")
                        .hasMessageContaining("out of range");
            });

    @Test
    void trigger() {
    }
}
