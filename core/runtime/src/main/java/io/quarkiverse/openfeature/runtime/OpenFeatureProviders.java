package io.quarkiverse.openfeature.runtime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.openfeature.sdk.FeatureProvider;

/**
 * The set of {@link FeatureProvider}s created for each OpenFeature domain.
 * <p>
 * A single instance is created at application startup and shared by all per-domain
 * services; it is also published statically, because the Dev UI and the JUnit extension
 * have no way to obtain it through the service graph.
 */
public final class OpenFeatureProviders {
    private static volatile OpenFeatureProviders current;

    private final Map<String, List<FeatureProvider>> providers = new ConcurrentHashMap<>();

    /**
     * Creates a fresh, empty instance and publishes it as the current one.
     */
    public static OpenFeatureProviders create() {
        OpenFeatureProviders result = new OpenFeatureProviders();
        current = result;
        return result;
    }

    /**
     * Returns the providers of the given {@code domain} in the current instance,
     * or an empty list if the domain is unknown or no instance exists yet.
     */
    public static List<FeatureProvider> get(String domain) {
        OpenFeatureProviders instance = current;
        if (instance == null) {
            return List.of();
        }
        return instance.providers.getOrDefault(domain, List.of());
    }

    private OpenFeatureProviders() {
    }

    void put(String domain, List<FeatureProvider> domainProviders) {
        providers.put(domain, List.copyOf(domainProviders));
    }
}
