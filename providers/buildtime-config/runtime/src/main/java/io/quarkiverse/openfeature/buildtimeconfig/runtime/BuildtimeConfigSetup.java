package io.quarkiverse.openfeature.buildtimeconfig.runtime;

import io.quarkiverse.openfeature.runtime.ConfigFeatureProvider;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;

public final class BuildtimeConfigSetup {
    public static final String NAME = "buildtime-config";

    public static FeatureProviderFactory createFactory(BuildtimeConfig config) {
        return domain -> {
            BuildtimeConfig.DomainBuildtimeConfig domainConfig = config.domains().get(domain);
            if (domainConfig == null) {
                throw new IllegalStateException("No buildtime configuration found for domain: " + domain);
            }
            return new ConfigFeatureProvider(NAME, domainConfig.buildtime());
        };
    }
}
