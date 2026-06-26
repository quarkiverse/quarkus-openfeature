package io.quarkiverse.openfeature.flagd.deployment;

import io.quarkiverse.openfeature.deployment.OpenFeatureProviderBuildItem;
import io.quarkiverse.openfeature.flagd.runtime.FlagdRecorder;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.tls.deployment.spi.TlsRegistryBuildItem;
import io.quarkus.vertx.deployment.VertxBuildItem;

class FlagdProcessor {
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    OpenFeatureProviderBuildItem provider(FlagdRecorder recorder, VertxBuildItem vertx,
            TlsRegistryBuildItem tlsRegistry) {
        return new OpenFeatureProviderBuildItem(FlagdRecorder.NAME,
                recorder.createFactory(vertx.getVertx(), tlsRegistry.registry()));
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
