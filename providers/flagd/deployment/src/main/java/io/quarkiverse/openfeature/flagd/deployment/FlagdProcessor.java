package io.quarkiverse.openfeature.flagd.deployment;

import io.quarkiverse.openfeature.deployment.OpenFeatureProviderBuildItem;
import io.quarkiverse.openfeature.flagd.runtime.FlagdConfig;
import io.quarkiverse.openfeature.flagd.runtime.FlagdSetup;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkus.arc.ArcContainer;
import io.quarkus.core.deployment.service.ServiceRegistrar;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;

class FlagdProcessor {
    @BuildStep
    OpenFeatureProviderBuildItem provider(ServiceRegistrar serviceRegistrar) {
        serviceRegistrar.forService(FeatureProviderFactory.class, FlagdSetup.NAME)
                .require(FlagdConfig.class)
                .require(Vertx.class)
                .require(TlsConfigurationRegistry.class)
                .require(ArcContainer.class)
                .onStart((ctx, config, vertx, tls, arc) -> FlagdSetup.createFactory(config, vertx, tls, arc));
        return new OpenFeatureProviderBuildItem(FlagdSetup.NAME);
    }

    @BuildStep
    void nativeImage(BuildProducer<ReflectiveClassBuildItem> reflectiveClasses,
            BuildProducer<NativeImageResourceBuildItem> resources) {
        // flagd-core uses Jackson to deserialize FeatureFlag via @JsonCreator
        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(
                "dev.openfeature.contrib.tools.flagd.core.model.FeatureFlag",
                "dev.openfeature.contrib.tools.flagd.core.model.StringSerializer")
                .constructors().methods().fields().build());

        // protobuf generated classes use reflection in FieldAccessorTable
        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(
                "dev.openfeature.flagd.grpc.sync.Sync$SyncFlagsRequest",
                "dev.openfeature.flagd.grpc.sync.Sync$SyncFlagsRequest$Builder",
                "dev.openfeature.flagd.grpc.sync.Sync$SyncFlagsResponse",
                "dev.openfeature.flagd.grpc.sync.Sync$SyncFlagsResponse$Builder",
                "com.google.protobuf.Struct",
                "com.google.protobuf.Struct$Builder",
                "com.google.protobuf.Value",
                "com.google.protobuf.Value$Builder",
                "com.google.protobuf.ListValue",
                "com.google.protobuf.ListValue$Builder")
                .constructors().methods().fields().build());

        // flagd-core's FlagParser loads JSON schemas for validation
        resources.produce(new NativeImageResourceBuildItem(
                "flagd/schemas/flags.json",
                "flagd/schemas/targeting.json"));
    }
}
