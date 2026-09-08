package io.quarkiverse.openfeature.buildtimeconfig.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.openfeature.buildtimeconfig.runtime.BuildtimeConfigRecorder;
import io.quarkus.test.QuarkusUnitTest;

public class BuildtimeConfigDomainNamedAfterProviderTest {
    private static final String DOMAIN = BuildtimeConfigRecorder.NAME;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.\"" + DOMAIN + "\".provider", DOMAIN)
            .overrideConfigKey("quarkus.openfeature.await-providers", "false")
            .assertException(e -> {
                assertThat(e).hasMessageContaining("OpenFeature domain \"" + DOMAIN + "\" must not be named after");
            });

    @Test
    void trigger() {
    }
}
