package io.quarkiverse.openfeature.flipt.deployment;

import io.quarkiverse.openfeature.deployment.OpenFeatureProviderBuildItem;
import io.quarkiverse.openfeature.flipt.runtime.FliptConfig;
import io.quarkiverse.openfeature.flipt.runtime.FliptSetup;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkus.arc.ArcContainer;
import io.quarkus.core.deployment.service.ServiceRegistrar;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;

class FliptProcessor {
    @BuildStep
    OpenFeatureProviderBuildItem provider(ServiceRegistrar serviceRegistrar) {
        serviceRegistrar.forService(FeatureProviderFactory.class, FliptSetup.NAME)
                .require(FliptConfig.class)
                .require(Vertx.class)
                .require(TlsConfigurationRegistry.class)
                .require(ArcContainer.class)
                .onStart((ctx, config, vertx, tls, arc) -> FliptSetup.createFactory(config, vertx, tls, arc));
        return new OpenFeatureProviderBuildItem(FliptSetup.NAME);
    }
}
