package io.quarkiverse.openfeature.unleash.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/**
 * A TLS configuration name that does not exist is a misconfiguration no reconnect can fix,
 * so it must fail the startup instead of turning into a connect timeout on the event loop.
 */
public class UnleashTlsConfigurationMissingTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.unleash.url", "https://unleash.example.com/api")
            .overrideConfigKey("quarkus.openfeature.unleash.tls-configuration-name", "nonexistent")
            .overrideConfigKey("quarkus.devservices.enabled", "false")
            .assertException(e -> {
                assertThat(e).hasMessageContaining("TLS configuration 'nonexistent' not found");
            });

    @Test
    void trigger() {
    }
}
