package io.quarkiverse.openfeature.runtimeconfig.deployment;

import io.quarkiverse.openfeature.deployment.OpenFeatureProviderBuildItem;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkiverse.openfeature.runtimeconfig.runtime.RuntimeConfig;
import io.quarkiverse.openfeature.runtimeconfig.runtime.RuntimeConfigSetup;
import io.quarkus.core.deployment.service.ServiceRegistrar;
import io.quarkus.deployment.annotations.BuildStep;

class RuntimeConfigProcessor {
    @BuildStep
    OpenFeatureProviderBuildItem provider(ServiceRegistrar serviceRegistrar) {
        serviceRegistrar.forService(FeatureProviderFactory.class, RuntimeConfigSetup.NAME)
                .require(RuntimeConfig.class)
                .onStart((ctx, config) -> RuntimeConfigSetup.createFactory(config));
        return new OpenFeatureProviderBuildItem(RuntimeConfigSetup.NAME);
    }
}
