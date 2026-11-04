package io.quarkiverse.openfeature.gofeatureflag.deployment;

import io.quarkiverse.openfeature.deployment.OpenFeatureProviderBuildItem;
import io.quarkiverse.openfeature.gofeatureflag.runtime.GoFeatureFlagConfig;
import io.quarkiverse.openfeature.gofeatureflag.runtime.GoFeatureFlagSetup;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkus.arc.ArcContainer;
import io.quarkus.core.deployment.service.ServiceRegistrar;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;

class GoFeatureFlagProcessor {
    @BuildStep
    OpenFeatureProviderBuildItem provider(ServiceRegistrar serviceRegistrar) {
        serviceRegistrar.forService(FeatureProviderFactory.class, GoFeatureFlagSetup.NAME)
                .require(GoFeatureFlagConfig.class)
                .require(Vertx.class)
                .require(TlsConfigurationRegistry.class)
                .require(ArcContainer.class)
                .onStart((ctx, config, vertx, tls, arc) -> GoFeatureFlagSetup.createFactory(config, vertx, tls, arc));
        return new OpenFeatureProviderBuildItem(GoFeatureFlagSetup.NAME);
    }
}
