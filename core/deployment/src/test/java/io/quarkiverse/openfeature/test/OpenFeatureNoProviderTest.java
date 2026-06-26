package io.quarkiverse.openfeature.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

public class OpenFeatureNoProviderTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .assertException(e -> {
                assertThat(e)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("No OpenFeature provider found");
            });

    @Test
    void trigger() {
    }
}
