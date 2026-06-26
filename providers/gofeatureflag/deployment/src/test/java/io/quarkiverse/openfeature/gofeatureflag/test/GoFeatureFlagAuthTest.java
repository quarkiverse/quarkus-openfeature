package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkiverse.openfeature.test.runtime.GoFeatureFlagTestContainer;
import io.quarkus.test.QuarkusUnitTest;

public class GoFeatureFlagAuthTest {
    private static final GoFeatureFlagTestContainer goff = new GoFeatureFlagTestContainer("goff-proxy-auth.yaml");

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("flags.goff.yaml"));

    static {
        test.setBeforeAllCustomizer(() -> {
            goff.start();

            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.url", goff.getConnectionInfo());
            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.api-key", "test-secret");
        });
        test.setAfterAllCustomizer(goff::stop);
    }

    @Inject
    Client client;

    @Test
    void booleanFlag() {
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
    }

    @Test
    void stringFlag() {
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello");
    }

    @Test
    void integerFlag() {
        assertThat(client.getIntegerValue("int-flag", 0)).isEqualTo(10);
    }

    @Test
    void doubleFlag() {
        assertThat(client.getDoubleValue("double-flag", 0.0)).isEqualTo(3.14);
    }

    @Test
    void disabledFlagReturnsDefault() {
        assertThat(client.getBooleanValue("disabled-flag", false)).isFalse();
    }

    @Test
    void missingFlagReturnsDefault() {
        assertThat(client.getStringValue("missing-flag", "fallback")).isEqualTo("fallback");
    }
}
