package io.quarkiverse.openfeature.buildtimeconfig.runtime;

import io.quarkiverse.openfeature.runtime.ConfigFeatureProvider;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class BuildtimeConfigRecorder {
    public static final String NAME = "buildtime-config";

    private final RuntimeValue<BuildtimeConfig> config;

    public BuildtimeConfigRecorder(RuntimeValue<BuildtimeConfig> config) {
        this.config = config;
    }

    public FeatureProviderFactory createFactory() {
        return domain -> {
            BuildtimeConfig.DomainBuildtimeConfig domainConfig = config.getValue().domains().get(domain);
            if (domainConfig == null) {
                throw new IllegalStateException("No buildtime configuration found for domain: " + domain);
            }
            return new ConfigFeatureProvider(NAME, domainConfig.buildtime());
        };
    }
}
