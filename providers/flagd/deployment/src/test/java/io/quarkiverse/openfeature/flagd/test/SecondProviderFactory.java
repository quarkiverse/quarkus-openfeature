package io.quarkiverse.openfeature.flagd.test;

import java.util.Map;

import dev.openfeature.sdk.FeatureProvider;
import io.quarkiverse.openfeature.runtime.ConfigFeatureProvider;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;

/**
 * A second provider to put next to flagd in a multi-provider domain.
 * <p>
 * It exists only so that the test does not have to pull another provider extension onto the
 * classpath: that would make every other test in this module fail with "multiple OpenFeature
 * providers found". The test registers it through a build chain customizer instead, so it is
 * invisible to all the other tests.
 */
public class SecondProviderFactory implements FeatureProviderFactory {
    static final String NAME = "second";

    @Override
    public FeatureProvider createProvider(String domain) {
        return new ConfigFeatureProvider(NAME, Map.of(
                // flagd knows this flag too and, being first, wins
                "string-flag", "hello from the second provider",
                "second-provider-flag", "only in the second provider"));
    }
}
