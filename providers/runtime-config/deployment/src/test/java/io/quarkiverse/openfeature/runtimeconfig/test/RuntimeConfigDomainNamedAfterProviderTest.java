package io.quarkiverse.openfeature.runtimeconfig.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.openfeature.runtimeconfig.runtime.RuntimeConfigRecorder;
import io.quarkus.test.QuarkusExtensionTest;

public class RuntimeConfigDomainNamedAfterProviderTest {
    private static final String DOMAIN = RuntimeConfigRecorder.NAME;

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .overrideConfigKey("quarkus.openfeature.\"" + DOMAIN + "\".provider", DOMAIN)
            .overrideConfigKey("quarkus.openfeature.await-providers", "false")
            .assertException(e -> {
                assertThat(e).hasMessageContaining("OpenFeature domain \"" + DOMAIN + "\" must not be named after");
            });

    @Test
    void trigger() {
    }
}
