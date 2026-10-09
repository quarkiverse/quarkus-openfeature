package io.quarkiverse.openfeature.unleash.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/**
 * A credentials provider that resolves to no token is a misconfiguration, not a
 * request for an unauthenticated connection: the user asked for these
 * credentials by name. Silently falling back to no API key would connect in the
 * clear against a server that happens not to require auth.
 */
public class UnleashEmptyCredentialsTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(TestCredentialsProvider.class))
            .overrideConfigKey("quarkus.openfeature.unleash.url", "http://localhost:4242")
            .overrideConfigKey("quarkus.openfeature.unleash.credentials-provider", "no-such-credentials")
            .overrideConfigKey("quarkus.devservices.enabled", "false")
            .assertException(e -> {
                assertThat(e).hasMessageContaining("no-such-credentials")
                        .hasMessageContaining("credentials-provider");
            });

    @Test
    void trigger() {
    }
}
