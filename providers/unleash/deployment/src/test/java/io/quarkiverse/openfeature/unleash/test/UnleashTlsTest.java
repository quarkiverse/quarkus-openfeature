package io.quarkiverse.openfeature.unleash.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkiverse.openfeature.test.runtime.TlsProxyExtension;
import io.quarkiverse.openfeature.test.runtime.UnleashTestContainer;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.certs.Format;
import io.smallrye.certs.junit5.Certificate;
import io.smallrye.certs.junit5.Certificates;

@Certificates(baseDir = "target/certs", certificates = @Certificate(name = "unleash-tls", formats = Format.PEM))
public class UnleashTlsTest {
    private static final UnleashTestContainer unleash = new UnleashTestContainer("unleash.json");

    @RegisterExtension
    @Order(1)
    static final TlsProxyExtension proxy = TlsProxyExtension.create("target/certs/unleash-tls.key",
            "target/certs/unleash-tls.crt", unleash, UnleashTestContainer.HTTP_PORT);

    @RegisterExtension
    @Order(2)
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("unleash.json"));

    static {
        test.setBeforeAllCustomizer(() -> {
            test.overrideConfigKey("quarkus.tls.unleash.trust-store.pem.certs", "target/certs/unleash-tls-ca.crt");

            test.overrideConfigKey("quarkus.openfeature.unleash.url", "https://localhost:" + proxy.port() + "/api");
            test.overrideConfigKey("quarkus.openfeature.unleash.api-key", UnleashTestContainer.API_TOKEN);
            test.overrideConfigKey("quarkus.openfeature.unleash.tls-configuration-name", "unleash");
        });
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
    void disabledFlagReturnsDefault() {
        assertThat(client.getBooleanValue("disabled-flag", false)).isFalse();
    }

    @Test
    void missingFlagReturnsDefault() {
        assertThat(client.getStringValue("missing-flag", "fallback")).isEqualTo("fallback");
    }
}
