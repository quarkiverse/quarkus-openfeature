package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkiverse.openfeature.test.runtime.GoFeatureFlagTestContainer;
import io.quarkus.test.QuarkusUnitTest;

/**
 * A directly configured {@code api-key} wins over a credentials provider, as
 * documented in {@code security.adoc}: the only provider present hands out a
 * token the relay proxy rejects, yet evaluation works.
 */
public class GoFeatureFlagCredentialsPrecedenceTest {
    private static final GoFeatureFlagTestContainer goff = new GoFeatureFlagTestContainer("goff-proxy-auth.yaml");

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root
                    .addClasses(WrongCredentialsProvider.class)
                    .addAsResource("flags.goff.yaml"));

    static {
        test.setBeforeAllCustomizer(() -> {
            goff.start();

            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.url", goff.getConnectionInfo());
            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.api-key", "test-secret");
            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.credentials-provider", "ignored");
        });
        test.setAfterAllCustomizer(goff::stop);
    }

    @Inject
    Client client;

    @Test
    void booleanFlag() {
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
    }
}
