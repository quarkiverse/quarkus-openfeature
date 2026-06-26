package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.FlagdTestContainer;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.certs.Format;
import io.smallrye.certs.junit5.Certificate;
import io.smallrye.certs.junit5.Certificates;

@Certificates(baseDir = "target/certs", certificates = {
        @Certificate(name = "flagd-good-tls", formats = Format.PEM),
        @Certificate(name = "flagd-bad-tls", formats = Format.PEM)
})
public class FlagdTlsBadTest {
    private static final FlagdTestContainer flagd = new FlagdTestContainer.Builder("flags.json")
            .withTls("target/certs/flagd-good-tls.key", "target/certs/flagd-good-tls.crt")
            .build();

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("flags.json"));

    static {
        test.setBeforeAllCustomizer(() -> {
            flagd.start();

            test.overrideConfigKey("quarkus.tls.flagd.trust-store.pem.certs", "target/certs/flagd-bad-tls-ca.crt");

            test.overrideConfigKey("quarkus.openfeature.flagd.url", flagd.getConnectionInfo());
            test.overrideConfigKey("quarkus.openfeature.flagd.tls-configuration-name", "flagd");
            test.overrideConfigKey("quarkus.openfeature.await-providers", "false");
        });
        test.setAfterAllCustomizer(flagd::stop);
    }

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
