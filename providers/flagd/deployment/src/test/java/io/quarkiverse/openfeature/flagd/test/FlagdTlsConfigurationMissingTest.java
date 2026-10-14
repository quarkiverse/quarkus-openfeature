package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/**
 * A TLS configuration name that does not exist is a misconfiguration no reconnect can fix,
 * so it must fail the startup instead of turning into a connect timeout on the event loop.
 */
public class FlagdTlsConfigurationMissingTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.flagd.url", "flagd.example.com:8015")
            .overrideConfigKey("quarkus.openfeature.flagd.tls-configuration-name", "nonexistent")
            .overrideConfigKey("quarkus.devservices.enabled", "false")
            .assertException(e -> {
                assertThat(e).hasMessageContaining("TLS configuration 'nonexistent' not found");
            });

    @Test
    void trigger() {
    }
}
