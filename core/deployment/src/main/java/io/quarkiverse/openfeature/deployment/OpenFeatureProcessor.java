package io.quarkiverse.openfeature.deployment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Singleton;

import dev.openfeature.sdk.Client;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkiverse.openfeature.runtime.OpenFeatureBuildTimeConfig;
import io.quarkiverse.openfeature.runtime.OpenFeatureBuildTimeConfig.DomainBuildTimeConfig;
import io.quarkiverse.openfeature.runtime.OpenFeatureProducer;
import io.quarkiverse.openfeature.runtime.OpenFeatureRecorder;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.smallrye.common.annotation.Identifier;

class OpenFeatureProcessor {
    private static final String FEATURE = "openfeature";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem beans() {
        return AdditionalBeanBuildItem.unremovableOf(OpenFeatureProducer.class);
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void configureProvidersAndClients(OpenFeatureBuildTimeConfig config,
            List<OpenFeatureProviderBuildItem> providers,
            OpenFeatureRecorder recorder,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans) {
        Map<String, FeatureProviderFactory> factories = new HashMap<>();
        for (OpenFeatureProviderBuildItem item : providers) {
            if (factories.put(item.getName(), item.getFactory()) != null) {
                throw new IllegalStateException(
                        "Duplicate OpenFeature provider name: " + item.getName());
            }
        }

        recorder.reset();

        DomainBuildTimeConfig defaultDomainConfig = config.domains()
                .getOrDefault(OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN, (DomainBuildTimeConfig) Optional::empty);
        processDomain(OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN, defaultDomainConfig, factories, recorder, syntheticBeans);

        for (Map.Entry<String, DomainBuildTimeConfig> entry : config.domains().entrySet()) {
            if (!OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN.equals(entry.getKey())) {
                processDomain(entry.getKey(), entry.getValue(), factories, recorder, syntheticBeans);
            }
        }
    }

    private void processDomain(String domain, DomainBuildTimeConfig domainConfig,
            Map<String, FeatureProviderFactory> factories,
            OpenFeatureRecorder recorder,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans) {
        List<FeatureProviderFactory> domainFactories = getProviderFactories(domain, domainConfig, factories);

        recorder.configureProvider(domain, domainFactories);

        SyntheticBeanBuildItem.ExtendedBeanConfigurator bean = SyntheticBeanBuildItem.configure(Client.class)
                .scope(Singleton.class)
                .setRuntimeInit()
                .unremovable();

        if (OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN.equals(domain)) {
            bean.supplier(recorder.createDefaultClient());
        } else {
            bean.addQualifier().annotation(Identifier.class).addValue("value", domain).done();
            bean.supplier(recorder.createClient(domain));
        }

        syntheticBeans.produce(bean.done());
    }

    private List<FeatureProviderFactory> getProviderFactories(String domain,
            DomainBuildTimeConfig domainConfig,
            Map<String, FeatureProviderFactory> factories) {
        boolean isDefault = OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN.equals(domain);
        String domainLabel = isDefault ? "default domain" : "domain \"" + domain + "\"";

        if (domainConfig.provider().isPresent()) {
            List<FeatureProviderFactory> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String name : domainConfig.provider().get()) {
                if (!seen.add(name)) {
                    throw new IllegalStateException(
                            "Duplicate provider \"" + name + "\" in configuration for " + domainLabel + ".");
                }
                FeatureProviderFactory factory = factories.get(name);
                if (factory == null) {
                    throw new IllegalStateException(
                            "Unknown OpenFeature provider \"" + name + "\" for " + domainLabel + ".\n"
                                    + "Available providers: " + factories.keySet());
                }
                result.add(factory);
            }
            return result;
        }

        if (factories.isEmpty()) {
            throw new IllegalStateException(
                    "No OpenFeature provider found for " + domainLabel + ".\n"
                            + "Add a provider extension such as "
                            + "quarkus-openfeature-runtime-config.");
        } else if (factories.size() > 1) {
            throw new IllegalStateException(
                    "Multiple OpenFeature providers found for " + domainLabel + ": " + factories.keySet()
                            + ".\nSet quarkus.openfeature."
                            + (isDefault ? "" : "\"" + domain + "\".")
                            + "provider to select which provider(s) to use.");
        } else {
            return new ArrayList<>(factories.values());
        }
    }
}
