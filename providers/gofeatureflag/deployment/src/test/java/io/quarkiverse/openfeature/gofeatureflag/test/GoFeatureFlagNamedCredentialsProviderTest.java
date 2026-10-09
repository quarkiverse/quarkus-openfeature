package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkiverse.openfeature.test.runtime.GoFeatureFlagTestContainer;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Two credentials providers are present and only one of them knows the token
 * the relay proxy accepts, so flag evaluation only works if
 * {@code credentials-provider-name} selects that one.
 */
public class GoFeatureFlagNamedCredentialsProviderTest {
    private static final GoFeatureFlagTestContainer goff = new GoFeatureFlagTestContainer("goff-proxy-auth.yaml");

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root
                    .addClasses(TestCredentialsProvider.class, WrongCredentialsProvider.class)
                    .addAsResource("flags.goff.yaml"));

    static {
        test.setBeforeAllCustomizer(() -> {
            goff.start();

            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.url", goff.getConnectionInfo());
            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.credentials-provider",
                    TestCredentialsProvider.CREDENTIALS_NAME);
            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.credentials-provider-name",
                    "test-credentials-provider");
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
