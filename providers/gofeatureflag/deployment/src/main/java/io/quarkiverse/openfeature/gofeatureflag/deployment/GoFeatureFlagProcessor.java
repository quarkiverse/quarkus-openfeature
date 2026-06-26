package io.quarkiverse.openfeature.gofeatureflag.deployment;

import io.quarkiverse.openfeature.deployment.OpenFeatureProviderBuildItem;
import io.quarkiverse.openfeature.gofeatureflag.runtime.GoFeatureFlagRecorder;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.tls.deployment.spi.TlsRegistryBuildItem;
import io.quarkus.vertx.deployment.VertxBuildItem;

class GoFeatureFlagProcessor {
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    OpenFeatureProviderBuildItem provider(GoFeatureFlagRecorder recorder, VertxBuildItem vertx,
            TlsRegistryBuildItem tlsRegistry) {
        return new OpenFeatureProviderBuildItem(GoFeatureFlagRecorder.NAME,
                recorder.createFactory(vertx.getVertx(), tlsRegistry.registry()));
    }
}
