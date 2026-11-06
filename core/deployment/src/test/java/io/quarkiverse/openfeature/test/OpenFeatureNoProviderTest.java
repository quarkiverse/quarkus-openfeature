package io.quarkiverse.openfeature.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

public class OpenFeatureNoProviderTest {
    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .assertException(e -> {
                assertThat(e)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("No OpenFeature provider found");
            });

    @Test
    void trigger() {
    }
}
