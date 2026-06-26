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
import io.smallrye.certs.Format;
import io.smallrye.certs.junit5.Certificate;
import io.smallrye.certs.junit5.Certificates;
import io.vertx.ext.web.client.WebClientOptions;

@Certificates(baseDir = "target/certs", certificates = @Certificate(name = "flipt-auth-tls", formats = Format.PEM))
public class FliptTlsAuthTest {
    private static final FliptTestContainer flipt = new FliptTestContainer.Builder("flipt-tls-auth-config.yml")
            .withTls("target/certs/flipt-auth-tls.key", "target/certs/flipt-auth-tls.crt")
            .build();

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("features.yaml"));

    static {
        test.setBeforeAllCustomizer(() -> {
            flipt.start();

            String url = flipt.getConnectionInfo();
            InputStream features = Thread.currentThread().getContextClassLoader().getResourceAsStream("features.yaml");
            FliptImporter.run(url, features, "test-secret", new WebClientOptions()
                    .setSsl(true)
                    .setTrustAll(true)
                    .setVerifyHost(false));

            test.overrideConfigKey("quarkus.tls.flipt.trust-store.pem.certs", "target/certs/flipt-auth-tls-ca.crt");

            test.overrideConfigKey("quarkus.openfeature.flipt.url", url);
            test.overrideConfigKey("quarkus.openfeature.flipt.auth-type", "client-token");
            test.overrideConfigKey("quarkus.openfeature.flipt.auth-token", "test-secret");
            test.overrideConfigKey("quarkus.openfeature.flipt.tls-configuration-name", "flipt");
        });
        test.setAfterAllCustomizer(flipt::stop);
    }

    @Inject
    Client client;

    @Test
    void booleanFlag() {
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
    }

    @Test
    void stringFlag() {
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("greeting");
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
