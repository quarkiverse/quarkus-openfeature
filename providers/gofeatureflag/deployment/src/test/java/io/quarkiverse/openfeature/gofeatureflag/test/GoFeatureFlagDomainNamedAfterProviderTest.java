package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.openfeature.gofeatureflag.runtime.GoFeatureFlagSetup;
import io.quarkus.test.QuarkusExtensionTest;

public class GoFeatureFlagDomainNamedAfterProviderTest {
    private static final String DOMAIN = GoFeatureFlagSetup.NAME;

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .overrideConfigKey("quarkus.openfeature.\"" + DOMAIN + "\".provider", DOMAIN)
            .overrideConfigKey("quarkus.devservices.enabled", "false")
            .overrideConfigKey("quarkus.openfeature.await-providers", "false")
            .assertException(e -> {
                assertThat(e).hasMessageContaining("OpenFeature domain \"" + DOMAIN + "\" must not be named after");
            });

    @Test
    void trigger() {
    }
}
