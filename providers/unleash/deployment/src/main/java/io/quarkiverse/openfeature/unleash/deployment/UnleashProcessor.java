package io.quarkiverse.openfeature.unleash.deployment;

import io.quarkiverse.openfeature.deployment.OpenFeatureProviderBuildItem;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkiverse.openfeature.unleash.runtime.UnleashConfig;
import io.quarkiverse.openfeature.unleash.runtime.UnleashSetup;
import io.quarkus.core.deployment.service.ServiceRegistrar;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.nativeimage.JniRuntimeAccessBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;

class UnleashProcessor {
    @BuildStep
    OpenFeatureProviderBuildItem provider(ServiceRegistrar serviceRegistrar) {
        serviceRegistrar.forService(FeatureProviderFactory.class, UnleashSetup.NAME)
                .require(UnleashConfig.class)
                .require(Vertx.class)
                .require(TlsConfigurationRegistry.class)
                .onStart((ctx, config, vertx, tls) -> UnleashSetup.createFactory(config, vertx, tls));
        return new OpenFeatureProviderBuildItem(UnleashSetup.NAME);
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
