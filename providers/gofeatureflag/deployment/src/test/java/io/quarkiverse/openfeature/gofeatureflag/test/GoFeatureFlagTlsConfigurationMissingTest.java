package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/**
 * A TLS configuration name that does not exist is a misconfiguration no reconnect can fix,
 * so it must fail the startup instead of turning into a connect timeout on the event loop.
 */
public class GoFeatureFlagTlsConfigurationMissingTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.gofeatureflag.url", "https://gofeatureflag.example.com:1031")
            .overrideConfigKey("quarkus.openfeature.gofeatureflag.tls-configuration-name", "nonexistent")
            .overrideConfigKey("quarkus.devservices.enabled", "false")
            .assertException(e -> {
                assertThat(e).hasMessageContaining("TLS configuration 'nonexistent' not found");
            });

    @Test
    void trigger() {
    }
}
