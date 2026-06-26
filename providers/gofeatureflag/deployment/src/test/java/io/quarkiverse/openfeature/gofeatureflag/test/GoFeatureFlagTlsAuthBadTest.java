package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.GoFeatureFlagTestContainer;
import io.quarkiverse.openfeature.test.runtime.TlsProxyExtension;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.certs.Format;
import io.smallrye.certs.junit5.Certificate;
import io.smallrye.certs.junit5.Certificates;

@Certificates(baseDir = "target/certs", certificates = @Certificate(name = "goff-tls-auth-bad", formats = Format.PEM))
public class GoFeatureFlagTlsAuthBadTest {
    private static final GoFeatureFlagTestContainer goff = new GoFeatureFlagTestContainer("goff-proxy-auth.yaml");

    @RegisterExtension
    @Order(1)
    static final TlsProxyExtension proxy = TlsProxyExtension.create("target/certs/goff-tls-auth-bad.key",
            "target/certs/goff-tls-auth-bad.crt", goff, GoFeatureFlagTestContainer.HTTP_PORT);

    @RegisterExtension
    @Order(2)
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("flags.goff.yaml"));

    static {
        test.setBeforeAllCustomizer(() -> {
            test.overrideConfigKey("quarkus.tls.goff.trust-store.pem.certs", "target/certs/goff-tls-auth-bad-ca.crt");

            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.url", "https://localhost:" + proxy.port());
            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.api-key", "wrong-token");
            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.tls-configuration-name", "goff");
            test.overrideConfigKey("quarkus.openfeature.await-providers", "false");
        });
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
