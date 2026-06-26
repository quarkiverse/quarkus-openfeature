package io.quarkiverse.openfeature.unleash.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.UnleashTestContainer;
import io.quarkus.test.QuarkusUnitTest;

public class UnleashAuthBadTest {
    private static final UnleashTestContainer unleash = new UnleashTestContainer("unleash.json");

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("unleash.json"));

    static {
        test.setBeforeAllCustomizer(() -> {
            unleash.start();

            test.overrideConfigKey("quarkus.openfeature.unleash.url", unleash.getConnectionInfo());
            test.overrideConfigKey("quarkus.openfeature.unleash.api-key", "wrong-token");
            test.overrideConfigKey("quarkus.openfeature.await-providers", "false");
        });
        test.setAfterAllCustomizer(unleash::stop);
    }

    @Inject
    Client client;

    @Test
    void providerInFatalState() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(client.getProviderState()).isEqualTo(ProviderState.FATAL);
        });
        assertThat(client.getBooleanValue("bool-flag", true)).isTrue();
        assertThat(client.getStringValue("string-flag", "fallback")).isEqualTo("fallback");
    }
}
