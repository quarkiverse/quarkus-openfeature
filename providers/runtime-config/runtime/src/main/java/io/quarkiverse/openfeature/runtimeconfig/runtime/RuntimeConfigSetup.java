package io.quarkiverse.openfeature.runtimeconfig.runtime;

import io.quarkiverse.openfeature.runtime.ConfigFeatureProvider;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;

public final class RuntimeConfigSetup {
    public static final String NAME = "runtime-config";

    public static FeatureProviderFactory createFactory(RuntimeConfig config) {
        return domain -> {
            RuntimeConfig.DomainRuntimeConfig domainConfig = config.domains().get(domain);
            if (domainConfig == null) {
                throw new IllegalStateException("No runtime configuration found for domain: " + domain);
            }
            return new ConfigFeatureProvider(NAME, domainConfig.runtime());
        };
    }
}
