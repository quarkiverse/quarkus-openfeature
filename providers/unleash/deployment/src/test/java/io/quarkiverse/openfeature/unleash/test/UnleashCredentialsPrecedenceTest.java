package io.quarkiverse.openfeature.unleash.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkiverse.openfeature.test.runtime.UnleashTestContainer;
import io.quarkus.test.QuarkusUnitTest;

/**
 * A directly configured {@code api-key} wins over a credentials provider, as
 * documented in {@code security.adoc}: the only provider present hands out a
 * token the server rejects, yet evaluation works.
 */
public class UnleashCredentialsPrecedenceTest {
    private static final UnleashTestContainer unleash = new UnleashTestContainer("unleash.json");

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root
                    .addClasses(WrongCredentialsProvider.class)
                    .addAsResource("unleash.json"));

    static {
        test.setBeforeAllCustomizer(() -> {
            unleash.start();

            test.overrideConfigKey("quarkus.openfeature.unleash.url", unleash.getConnectionInfo());
            test.overrideConfigKey("quarkus.openfeature.unleash.api-key", UnleashTestContainer.API_TOKEN);
            test.overrideConfigKey("quarkus.openfeature.unleash.credentials-provider", "ignored");
        });
        test.setAfterAllCustomizer(unleash::stop);
    }

    @Inject
    Client client;

    @Test
    void booleanFlag() {
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
    }
}
