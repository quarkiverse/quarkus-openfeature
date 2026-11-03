package io.quarkiverse.openfeature.runtimeconfig.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

public class RuntimeConfigDuplicateProviderTest {
    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .overrideConfigKey("quarkus.openfeature.provider", "runtime-config,runtime-config")
            .assertException(e -> {
                assertThat(e)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Duplicate provider");
            });

    @Test
    void trigger() {
    }
}
