package io.quarkiverse.openfeature.deployment;

import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkus.builder.item.MultiBuildItem;

public final class OpenFeatureProviderBuildItem extends MultiBuildItem {
    private final String name;
    private final FeatureProviderFactory factory;

    public OpenFeatureProviderBuildItem(String name, FeatureProviderFactory factory) {
        this.name = name;
        this.factory = factory;
    }

    public String getName() {
        return name;
    }

    public FeatureProviderFactory getFactory() {
        return factory;
    }
}
