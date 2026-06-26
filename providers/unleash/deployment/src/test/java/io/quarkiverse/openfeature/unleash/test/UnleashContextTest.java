package io.quarkiverse.openfeature.unleash.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkiverse.openfeature.test.runtime.UnleashTestContainer;
import io.quarkus.test.QuarkusUnitTest;

public class UnleashContextTest {
    private static final UnleashTestContainer unleash = new UnleashTestContainer("unleash-context.json");

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("unleash-context.json"));

    static {
        test.setBeforeAllCustomizer(() -> {
            unleash.start();

            test.overrideConfigKey("quarkus.openfeature.unleash.url", unleash.getConnectionInfo());
            test.overrideConfigKey("quarkus.openfeature.unleash.api-key", UnleashTestContainer.API_TOKEN);
            test.overrideConfigKey("quarkus.openfeature.unleash.app-name", "test-app");
            test.overrideConfigKey("quarkus.openfeature.unleash.environment", "staging");
        });
        test.setAfterAllCustomizer(unleash::stop);
    }

    @Inject
    Client client;

    @Test
    void appNameConstraintMatches() {
        assertThat(client.getStringValue("app-name-flag", "unexpected")).isEqualTo("matched");
    }

    @Test
    void environmentConstraintDoesNotMatch() {
        assertThat(client.getStringValue("env-flag", "unexpected")).isEqualTo("not-matched");
    }
}
