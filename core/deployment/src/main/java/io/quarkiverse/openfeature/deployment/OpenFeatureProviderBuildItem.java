package io.quarkiverse.openfeature.deployment;

import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkus.builder.item.MultiBuildItem;

/**
 * Declares that a provider extension installs a {@link FeatureProviderFactory} service
 * under the given name.
 */
public final class OpenFeatureProviderBuildItem extends MultiBuildItem {
    private final String name;

    public OpenFeatureProviderBuildItem(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
