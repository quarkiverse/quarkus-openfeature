package io.quarkiverse.openfeature.flipt.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/**
 * A credentials provider that resolves to no token is a misconfiguration, not a
 * request for an unauthenticated connection. The Unleash and GO Feature Flag
 * counterparts of this test cover the same rule.
 */
public class FliptEmptyCredentialsTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(TestCredentialsProvider.class))
            .overrideConfigKey("quarkus.openfeature.flipt.url", "http://localhost:8080")
            .overrideConfigKey("quarkus.openfeature.flipt.auth-type", "client-token")
            .overrideConfigKey("quarkus.openfeature.flipt.credentials-provider", "no-such-credentials")
            .overrideConfigKey("quarkus.devservices.enabled", "false")
            .assertException(e -> {
                assertThat(e).hasMessageContaining("no token was provided");
            });

    @Test
    void trigger() {
    }
}
