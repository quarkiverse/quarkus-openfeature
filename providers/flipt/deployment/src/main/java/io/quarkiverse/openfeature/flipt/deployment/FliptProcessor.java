package io.quarkiverse.openfeature.flipt.deployment;

import io.quarkiverse.openfeature.deployment.OpenFeatureProviderBuildItem;
import io.quarkiverse.openfeature.flipt.runtime.FliptRecorder;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.tls.deployment.spi.TlsRegistryBuildItem;
import io.quarkus.vertx.deployment.VertxBuildItem;

class FliptProcessor {
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    OpenFeatureProviderBuildItem provider(FliptRecorder recorder, VertxBuildItem vertx,
            TlsRegistryBuildItem tlsRegistry) {
        return new OpenFeatureProviderBuildItem(FliptRecorder.NAME,
                recorder.createFactory(vertx.getVertx(), tlsRegistry.registry()));
    }
}
