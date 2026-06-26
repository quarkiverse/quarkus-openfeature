package io.quarkiverse.openfeature.runtimeconfig.runtime;

import io.quarkiverse.openfeature.runtime.ConfigFeatureProvider;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class RuntimeConfigRecorder {
    public static final String NAME = "runtime-config";

    private final RuntimeValue<RuntimeConfig> config;

    public RuntimeConfigRecorder(RuntimeValue<RuntimeConfig> config) {
        this.config = config;
    }

    public FeatureProviderFactory createFactory() {
        return domain -> {
            RuntimeConfig.DomainRuntimeConfig domainConfig = config.getValue().domains().get(domain);
            if (domainConfig == null) {
                throw new IllegalStateException("No runtime configuration found for domain: " + domain);
            }
            return new ConfigFeatureProvider(NAME, domainConfig.runtime());
        };
    }
}
