package io.quarkiverse.openfeature.buildtimeconfig.deployment;

import io.quarkiverse.openfeature.buildtimeconfig.runtime.BuildtimeConfigRecorder;
import io.quarkiverse.openfeature.deployment.OpenFeatureProviderBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;

class BuildtimeConfigProcessor {
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    OpenFeatureProviderBuildItem provider(BuildtimeConfigRecorder recorder) {
        return new OpenFeatureProviderBuildItem(BuildtimeConfigRecorder.NAME, recorder.createFactory());
    }
}
