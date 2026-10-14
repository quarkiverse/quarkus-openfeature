package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/**
 * An {@code https://} URL must not silently become a cleartext gRPC connection. The flagd
 * sync protocol has no HTTPS form, so the only honest answer is to refuse to start.
 * <p>
 * Awaiting providers is off, as it is in production, so that the refusal does not depend
 * on it.
 */
public class FlagdHttpsUrlSchemeTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "false")
            .overrideConfigKey("quarkus.openfeature.flagd.url", "https://flagd.example.com:8015")
            .overrideConfigKey("quarkus.devservices.enabled", "false")
            .assertException(e -> {
                assertThat(e).hasMessageContaining("Unsupported flagd URL scheme 'https'")
                        .hasMessageContaining("tls-configuration-name");
            });

    @Test
    void trigger() {
    }
}
