package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkus.test.QuarkusUnitTest;

public class GoFeatureFlagBadUrlTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.gofeatureflag.url", "http://localhost:277")
            .overrideConfigKey("quarkus.openfeature.await-providers", "false");

    @Inject
    Client client;

    @Test
    void providerInErrorState() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(client.getProviderState()).isEqualTo(ProviderState.ERROR);
        });
        assertThat(client.getBooleanValue("any-flag", true)).isTrue();
        assertThat(client.getStringValue("any-flag", "fallback")).isEqualTo("fallback");
    }
}
