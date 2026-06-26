package io.quarkiverse.openfeature.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.openfeature.deployment.OpenFeatureProviderBuildItem;
import io.quarkus.test.QuarkusUnitTest;

public class OpenFeatureMultipleProvidersTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .addBuildChainCustomizer(builder -> {
                builder.addBuildStep(context -> {
                    context.produce(new OpenFeatureProviderBuildItem("provider-a", null));
                    context.produce(new OpenFeatureProviderBuildItem("provider-b", null));
                })
                        .produces(OpenFeatureProviderBuildItem.class)
                        .build();
            })
            .assertException(e -> {
                assertThat(e)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Multiple OpenFeature providers found");
            });

    @Test
    void trigger() {
    }
}
