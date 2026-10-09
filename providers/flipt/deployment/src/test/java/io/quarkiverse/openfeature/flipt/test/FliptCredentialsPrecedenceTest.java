package io.quarkiverse.openfeature.flipt.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkiverse.openfeature.flipt.deployment.FliptImporter;
import io.quarkiverse.openfeature.test.runtime.FliptTestContainer;
import io.quarkus.test.QuarkusUnitTest;

/**
 * A directly configured {@code auth-token} wins over a credentials provider,
 * as documented in {@code security.adoc}: the only provider present hands out
 * a token the server rejects, yet evaluation works.
 */
public class FliptCredentialsPrecedenceTest {
    private static final FliptTestContainer flipt = new FliptTestContainer.Builder("flipt-auth-config.yml").build();

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root
                    .addClasses(WrongCredentialsProvider.class)
                    .addAsResource("features.yaml"));

    static {
        test.setBeforeAllCustomizer(() -> {
            flipt.start();

            String url = flipt.getConnectionInfo();
            InputStream features = Thread.currentThread().getContextClassLoader().getResourceAsStream("features.yaml");
            FliptImporter.run(url, features, "test-secret", null);

            test.overrideConfigKey("quarkus.openfeature.flipt.url", url);
            test.overrideConfigKey("quarkus.openfeature.flipt.auth-type", "client-token");
            test.overrideConfigKey("quarkus.openfeature.flipt.auth-token", "test-secret");
            test.overrideConfigKey("quarkus.openfeature.flipt.credentials-provider", "ignored");
        });
        test.setAfterAllCustomizer(flipt::stop);
    }

    @Inject
    Client client;

    @Test
    void booleanFlag() {
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
    }
}
