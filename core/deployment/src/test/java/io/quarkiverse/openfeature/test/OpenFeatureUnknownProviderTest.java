package io.quarkiverse.openfeature.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

public class OpenFeatureUnknownProviderTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
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
