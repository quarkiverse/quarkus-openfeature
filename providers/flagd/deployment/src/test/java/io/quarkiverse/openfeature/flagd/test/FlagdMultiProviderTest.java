package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.deployment.OpenFeatureProviderBuildItem;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Evaluation in a domain that combines a remote-sync provider with another provider.
 * <p>
 * The lifecycle of such a domain is a separate story; see {@link FlagdMultiProviderEventsTest}.
 */
public class FlagdMultiProviderTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("flags.json").addClass(SecondProviderFactory.class))
            .addBuildChainCustomizer(builder -> {
                builder.addBuildStep(context -> {
                    context.produce(new OpenFeatureProviderBuildItem(SecondProviderFactory.NAME,
                            new SecondProviderFactory()));
                })
                        .produces(OpenFeatureProviderBuildItem.class)
                        .build();
            })
            // flagd comes first, so it wins for every flag it knows
            .overrideConfigKey("quarkus.openfeature.provider", "flagd," + SecondProviderFactory.NAME);

    @Inject
    Client client;

    @Test
    void providerIsReady() {
        assertThat(client.getProviderState()).isEqualTo(ProviderState.READY);
    }

    @Test
    void theFirstProviderThatKnowsTheFlagWins() {
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello from flagd");
    }

    @Test
    void aFlagUnknownToFlagdFallsThroughToTheNextProvider() {
        assertThat(client.getStringValue("second-provider-flag", "default")).isEqualTo("only in the second provider");
    }

    @Test
    void unknownFlagFallsBackToTheCallerDefault() {
        assertThat(client.getStringValue("no-such-flag", "default")).isEqualTo("default");
    }

    // `@TestFlag` is deliberately not exercised here: it does not work under `QuarkusUnitTest`
    // at all, for the same reason it does not work under `@QuarkusIntegrationTest`. The
    // multi-provider `@TestFlag` coverage lives in `TestFlagAnnotationTest` instead.
}
