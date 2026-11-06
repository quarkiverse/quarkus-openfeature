package io.quarkiverse.openfeature.flagd.test;

import io.quarkiverse.openfeature.deployment.OpenFeatureProviderBuildItem;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.core.deployment.service.ServiceRegistrar;
import io.quarkus.core.deployment.service.impl.ServiceMetadataBuildItem;
import io.quarkus.core.deployment.service.impl.ServiceRegistrarImpl;
import io.quarkus.core.deployment.service.impl.ServiceValueRetentionBuildItem;
import io.quarkus.core.deployment.service.impl.StaticServiceMetadataBuildItem;
import io.quarkus.deployment.builditem.MainBytecodeRecorderBuildItem;
import io.quarkus.deployment.builditem.StaticBytecodeRecorderBuildItem;

/**
 * Registers {@link SecondProviderFactory} the same way a provider extension would:
 * as a {@link FeatureProviderFactory} service named after the provider, plus the build item that
 * tells the core extension the name exists.
 * <p>
 * A test build chain customizer is not a {@code @BuildStep} method, so the
 * {@link ServiceRegistrar} has to be created by hand.
 */
final class SecondProviderBuildStep {
    // TODO this uses internal classes that are not public API, need to find a better way
    static void register(BuildChainBuilder builder) {
        builder.addBuildStep(context -> {
            ServiceRegistrar serviceRegistrar = new ServiceRegistrarImpl(
                    context::produce, context::produce, context::produce, context::produce, context::produce,
                    SecondProviderBuildStep.class.getName());
            serviceRegistrar.forService(FeatureProviderFactory.class, SecondProviderFactory.NAME)
                    .onStart(ctx -> new SecondProviderFactory());
            context.produce(new OpenFeatureProviderBuildItem(SecondProviderFactory.NAME));
        })
                .produces(OpenFeatureProviderBuildItem.class)
                .produces(MainBytecodeRecorderBuildItem.class)
                .produces(StaticBytecodeRecorderBuildItem.class)
                .produces(ServiceMetadataBuildItem.class)
                .produces(StaticServiceMetadataBuildItem.class)
                .produces(ServiceValueRetentionBuildItem.class)
                .build();
    }

    private SecondProviderBuildStep() {
    }
}
