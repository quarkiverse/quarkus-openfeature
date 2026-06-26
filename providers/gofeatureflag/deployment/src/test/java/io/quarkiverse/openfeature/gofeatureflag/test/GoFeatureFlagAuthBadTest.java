package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.GoFeatureFlagTestContainer;
import io.quarkus.test.QuarkusUnitTest;

public class GoFeatureFlagAuthBadTest {
    private static final GoFeatureFlagTestContainer goff = new GoFeatureFlagTestContainer("goff-proxy-auth.yaml");

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("flags.goff.yaml"));

    static {
        test.setBeforeAllCustomizer(() -> {
            goff.start();

            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.url", goff.getConnectionInfo());
            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.api-key", "wrong-token");
            test.overrideConfigKey("quarkus.openfeature.await-providers", "false");
        });
        test.setAfterAllCustomizer(goff::stop);
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
