package io.quarkiverse.openfeature.unleash.deployment;

import io.quarkiverse.openfeature.deployment.OpenFeatureProviderBuildItem;
import io.quarkiverse.openfeature.unleash.runtime.UnleashRecorder;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.nativeimage.JniRuntimeAccessBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.tls.deployment.spi.TlsRegistryBuildItem;
import io.quarkus.vertx.deployment.VertxBuildItem;

class UnleashProcessor {
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    OpenFeatureProviderBuildItem provider(UnleashRecorder recorder, VertxBuildItem vertx,
            TlsRegistryBuildItem tlsRegistry) {
        return new OpenFeatureProviderBuildItem(UnleashRecorder.NAME,
                recorder.createFactory(vertx.getVertx(), tlsRegistry.registry()));
    }

    @BuildStep
    void nativeImage(BuildProducer<JniRuntimeAccessBuildItem> jniClasses,
            BuildProducer<RuntimeInitializedClassBuildItem> runtimeInitializedClasses) {
        // yggdrasil engine uses JNI to call into a Rust native library
        jniClasses.produce(new JniRuntimeAccessBuildItem(false, true, false,
                "io.getunleash.engine.NativeBridge"));
        // native code throws NativeException via JNI
        jniClasses.produce(new JniRuntimeAccessBuildItem(true, false, false,
                "io.getunleash.engine.NativeException"));
        // NativeBridge's static initializer loads the native library
        runtimeInitializedClasses.produce(new RuntimeInitializedClassBuildItem(
                "io.getunleash.engine.NativeBridge"));
        // UnleashEngine has a static Cleaner that creates a daemon thread
        runtimeInitializedClasses.produce(new RuntimeInitializedClassBuildItem(
                "io.getunleash.engine.UnleashEngine"));
    }
}
