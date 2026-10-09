package io.quarkiverse.openfeature.unleash.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkiverse.openfeature.test.runtime.UnleashTestContainer;
import io.quarkus.test.QuarkusUnitTest;

/**
 * The API key comes from a credentials provider instead of {@code api-key}.
 */
public class UnleashCredentialsProviderTest {
    private static final UnleashTestContainer unleash = new UnleashTestContainer("unleash.json");

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root
                    .addClasses(TestCredentialsProvider.class)
                    .addAsResource("unleash.json"));

    static {
        test.setBeforeAllCustomizer(() -> {
            unleash.start();

            test.overrideConfigKey("quarkus.openfeature.unleash.url", unleash.getConnectionInfo());
            test.overrideConfigKey("quarkus.openfeature.unleash.credentials-provider",
                    TestCredentialsProvider.CREDENTIALS_NAME);
        });
        test.setAfterAllCustomizer(unleash::stop);
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
