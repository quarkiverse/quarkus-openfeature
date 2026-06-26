package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkiverse.openfeature.test.runtime.GoFeatureFlagTestContainer;
import io.quarkiverse.openfeature.test.runtime.TlsProxyExtension;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.certs.Format;
import io.smallrye.certs.junit5.Certificate;
import io.smallrye.certs.junit5.Certificates;

@Certificates(baseDir = "target/certs", certificates = @Certificate(name = "goff-tls", formats = Format.PEM))
public class GoFeatureFlagTlsTest {
    private static final GoFeatureFlagTestContainer goff = new GoFeatureFlagTestContainer("goff-proxy.yaml");

    @RegisterExtension
    @Order(1)
    static final TlsProxyExtension proxy = TlsProxyExtension.create("target/certs/goff-tls.key",
            "target/certs/goff-tls.crt", goff, GoFeatureFlagTestContainer.HTTP_PORT);

    @RegisterExtension
    @Order(2)
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("flags.goff.yaml"));

    static {
        test.setBeforeAllCustomizer(() -> {
            test.overrideConfigKey("quarkus.tls.goff.trust-store.pem.certs", "target/certs/goff-tls-ca.crt");

            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.url", "https://localhost:" + proxy.port());
            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.tls-configuration-name", "goff");
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
