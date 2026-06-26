package io.quarkiverse.openfeature.unleash.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.TlsProxyExtension;
import io.quarkiverse.openfeature.test.runtime.UnleashTestContainer;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.certs.Format;
import io.smallrye.certs.junit5.Certificate;
import io.smallrye.certs.junit5.Certificates;

@Certificates(baseDir = "target/certs", certificates = @Certificate(name = "unleash-tls-auth-bad", formats = Format.PEM))
public class UnleashTlsAuthBadTest {
    private static final UnleashTestContainer unleash = new UnleashTestContainer("unleash.json");

    @RegisterExtension
    @Order(1)
    static final TlsProxyExtension proxy = TlsProxyExtension.create("target/certs/unleash-tls-auth-bad.key",
            "target/certs/unleash-tls-auth-bad.crt", unleash, UnleashTestContainer.HTTP_PORT);

    @RegisterExtension
    @Order(2)
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("unleash.json"));

    static {
        test.setBeforeAllCustomizer(() -> {
            test.overrideConfigKey("quarkus.tls.unleash.trust-store.pem.certs", "target/certs/unleash-tls-auth-bad-ca.crt");

            test.overrideConfigKey("quarkus.openfeature.unleash.url", "https://localhost:" + proxy.port() + "/api");
            test.overrideConfigKey("quarkus.openfeature.unleash.api-key", "wrong-token");
            test.overrideConfigKey("quarkus.openfeature.unleash.tls-configuration-name", "unleash");
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
