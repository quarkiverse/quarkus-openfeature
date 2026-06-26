package io.quarkiverse.openfeature.flipt.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.FliptTestContainer;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.certs.Format;
import io.smallrye.certs.junit5.Certificate;
import io.smallrye.certs.junit5.Certificates;

@Certificates(baseDir = "target/certs", certificates = @Certificate(name = "flipt-tls-auth-bad", formats = Format.PEM))
public class FliptTlsAuthBadTest {
    private static final FliptTestContainer flipt = new FliptTestContainer.Builder("flipt-tls-auth-config.yml")
            .withTls("target/certs/flipt-tls-auth-bad.key", "target/certs/flipt-tls-auth-bad.crt")
            .build();

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("features.yaml"));

    static {
        test.setBeforeAllCustomizer(() -> {
            flipt.start();

            test.overrideConfigKey("quarkus.tls.flipt.trust-store.pem.certs", "target/certs/flipt-tls-auth-bad-ca.crt");

            test.overrideConfigKey("quarkus.openfeature.flipt.url", flipt.getConnectionInfo());
            test.overrideConfigKey("quarkus.openfeature.flipt.auth-type", "client-token");
            test.overrideConfigKey("quarkus.openfeature.flipt.auth-token", "wrong-token");
            test.overrideConfigKey("quarkus.openfeature.flipt.tls-configuration-name", "flipt");
            test.overrideConfigKey("quarkus.openfeature.await-providers", "false");
        });
        test.setAfterAllCustomizer(flipt::stop);
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
