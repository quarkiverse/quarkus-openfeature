package io.quarkiverse.openfeature.runtime;

import dev.openfeature.sdk.FeatureProvider;

public interface FeatureProviderFactory {
    /**
     * Creates a {@link FeatureProvider} for the given {@code domain}.
     *
     * @param domain the name of the domain, must not be {@code null}
     */
    FeatureProvider createProvider(String domain);
}
