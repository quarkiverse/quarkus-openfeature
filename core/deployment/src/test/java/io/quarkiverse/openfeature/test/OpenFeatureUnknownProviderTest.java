package io.quarkiverse.openfeature.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

public class OpenFeatureUnknownProviderTest {
    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .overrideConfigKey("quarkus.openfeature.provider", "nonexistent")
            .assertException(e -> {
                assertThat(e)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Unknown OpenFeature provider")
                        .hasMessageContaining("nonexistent");
            });

    @Test
    void trigger() {
    }
}
