package io.quarkiverse.openfeature.runtimeconfig.deployment;

import io.quarkiverse.openfeature.deployment.OpenFeatureProviderBuildItem;
import io.quarkiverse.openfeature.runtimeconfig.runtime.RuntimeConfigRecorder;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;

class RuntimeConfigProcessor {
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    OpenFeatureProviderBuildItem provider(RuntimeConfigRecorder recorder) {
        return new OpenFeatureProviderBuildItem(RuntimeConfigRecorder.NAME, recorder.createFactory());
    }
}
