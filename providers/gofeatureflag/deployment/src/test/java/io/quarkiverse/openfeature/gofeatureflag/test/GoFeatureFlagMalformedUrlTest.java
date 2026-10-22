package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/**
 * A GO Feature Flag URL without a scheme is a typo, not a server that happens to be down, so it must
 * fail the application startup with a message that points at the configuration.
 * <p>
 * Awaiting providers is off, as it is in production, so that the refusal does not depend on it.
 */
public class GoFeatureFlagMalformedUrlTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "false")
            .overrideConfigKey("quarkus.openfeature.gofeatureflag.url", "localhost:1031")
            .overrideConfigKey("quarkus.devservices.enabled", "false")
            .assertException(e -> {
                assertThat(e).hasMessageContaining("Unsupported GO Feature Flag URL scheme 'localhost'")
                        .hasMessageContaining("localhost:1031");
            });

    @Test
    void trigger() {
    }
}
