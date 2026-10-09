package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkiverse.openfeature.test.runtime.GoFeatureFlagTestContainer;
import io.quarkus.test.QuarkusUnitTest;

/**
 * The API key comes from a credentials provider instead of {@code api-key}.
 */
public class GoFeatureFlagCredentialsProviderTest {
    private static final GoFeatureFlagTestContainer goff = new GoFeatureFlagTestContainer("goff-proxy-auth.yaml");

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root
                    .addClasses(TestCredentialsProvider.class)
                    .addAsResource("flags.goff.yaml"));

    static {
        test.setBeforeAllCustomizer(() -> {
            goff.start();

            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.url", goff.getConnectionInfo());
            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.credentials-provider",
                    TestCredentialsProvider.CREDENTIALS_NAME);
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
}
