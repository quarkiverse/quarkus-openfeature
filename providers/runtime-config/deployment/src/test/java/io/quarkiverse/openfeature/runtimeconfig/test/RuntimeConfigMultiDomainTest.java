package io.quarkiverse.openfeature.runtimeconfig.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.common.annotation.Identifier;

public class RuntimeConfigMultiDomainTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.runtime.default-flag", "from-default")
            .overrideConfigKey("quarkus.openfeature.\"custom\".provider", "runtime-config")
            .overrideConfigKey("quarkus.openfeature.\"custom\".runtime.custom-flag", "from-custom");

    @Inject
    Client defaultClient;

    @Inject
    @Identifier("custom")
    Client customClient;

    @Test
    void defaultDomainFlag() {
        assertThat(defaultClient.getStringValue("default-flag", "fallback")).isEqualTo("from-default");
    }

    @Test
    void defaultDomainDoesNotSeeCustomFlags() {
        assertThat(defaultClient.getStringValue("custom-flag", "fallback")).isEqualTo("fallback");
    }

    @Test
    void customDomainFlag() {
        assertThat(customClient.getStringValue("custom-flag", "fallback")).isEqualTo("from-custom");
    }

    @Test
    void customDomainDoesNotSeeDefaultFlags() {
        assertThat(customClient.getStringValue("default-flag", "fallback")).isEqualTo("fallback");
    }
}
