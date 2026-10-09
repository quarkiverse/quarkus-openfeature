package io.quarkiverse.openfeature.runtimeconfig.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.common.annotation.Identifier;

/**
 * Declaring a domain with an empty provider list is enough to create it; the provider is then
 * selected the same way as for the default domain, which works when exactly one provider
 * extension is on the classpath.
 */
public class RuntimeConfigDomainWithEmptyProviderTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.\"custom\".provider", "")
            .overrideConfigKey("quarkus.openfeature.\"custom\".runtime.custom-flag", "from-custom");

    @Inject
    @Identifier("custom")
    Client customClient;

    @Test
    void customDomainFlag() {
        assertThat(customClient.getStringValue("custom-flag", "fallback")).isEqualTo("from-custom");
    }
}
