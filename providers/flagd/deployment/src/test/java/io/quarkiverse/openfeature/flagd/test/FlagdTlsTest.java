package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkiverse.openfeature.test.runtime.FlagdTestContainer;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.certs.Format;
import io.smallrye.certs.junit5.Certificate;
import io.smallrye.certs.junit5.Certificates;

@Certificates(baseDir = "target/certs", certificates = @Certificate(name = "flagd-tls", formats = Format.PEM))
public class FlagdTlsTest {
    private static final FlagdTestContainer flagd = new FlagdTestContainer.Builder("flags.json")
            .withTls("target/certs/flagd-tls.key", "target/certs/flagd-tls.crt")
            .build();

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("flags.json"));

    static {
        test.setBeforeAllCustomizer(() -> {
            flagd.start();

            test.overrideConfigKey("quarkus.tls.flagd.trust-store.pem.certs", "target/certs/flagd-tls-ca.crt");

            test.overrideConfigKey("quarkus.openfeature.flagd.url", flagd.getConnectionInfo());
            test.overrideConfigKey("quarkus.openfeature.flagd.tls-configuration-name", "flagd");
        });
        test.setAfterAllCustomizer(flagd::stop);
    }

    @Inject
    Client client;

    @Test
    void booleanFlag() {
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
    }

    @Test
    void stringFlag() {
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello from flagd");
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
