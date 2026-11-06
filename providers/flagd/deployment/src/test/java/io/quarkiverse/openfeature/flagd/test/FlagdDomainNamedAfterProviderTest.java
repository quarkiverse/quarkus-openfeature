package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.openfeature.flagd.runtime.FlagdSetup;
import io.quarkus.test.QuarkusExtensionTest;

public class FlagdDomainNamedAfterProviderTest {
    private static final String DOMAIN = FlagdSetup.NAME;

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
