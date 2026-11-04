package io.quarkiverse.openfeature.buildtimeconfig.deployment;

import io.quarkiverse.openfeature.buildtimeconfig.runtime.BuildtimeConfig;
import io.quarkiverse.openfeature.buildtimeconfig.runtime.BuildtimeConfigSetup;
import io.quarkiverse.openfeature.deployment.OpenFeatureProviderBuildItem;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkus.core.deployment.service.ServiceRegistrar;
import io.quarkus.deployment.annotations.BuildStep;

class BuildtimeConfigProcessor {
    @BuildStep
    OpenFeatureProviderBuildItem provider(ServiceRegistrar serviceRegistrar, BuildtimeConfig config) {
        serviceRegistrar.forService(FeatureProviderFactory.class, BuildtimeConfigSetup.NAME)
                .onStart(ctx -> BuildtimeConfigSetup.createFactory(config));
        return new OpenFeatureProviderBuildItem(BuildtimeConfigSetup.NAME);
    }
}
