package io.quarkiverse.openfeature.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.exceptions.OpenFeatureError;
import dev.openfeature.sdk.multiprovider.MultiProvider;
import io.quarkus.runtime.LaunchMode;

/**
 * Runtime entrypoints of the OpenFeature extension, called from the startup services
 * registered by {@code OpenFeatureProcessor}.
 */
public final class OpenFeatureSetup {
    private static final Logger log = Logger.getLogger(OpenFeatureSetup.class);

    /**
     * Creates the providers of the given {@code domain} and registers them with the OpenFeature API.
     *
     * @param domain the domain name
     * @param providerNames names of the providers configured for the domain, in configuration order
     * @param factories all provider factories present in the application, keyed by provider name
     * @param registry the registry to record the created providers in
     * @param config the runtime configuration
     */
    public static FeatureProvider configureDomain(String domain, List<String> providerNames,
            Map<String, FeatureProviderFactory> factories, OpenFeatureProviders registry,
            OpenFeatureRuntimeConfig config) {
        FeatureProvider provider = createProvider(domain, providerNames, factories, registry);

        boolean shouldAwaitProviders = switch (config.awaitProviders()) {
            case TRUE -> true;
            case FALSE -> false;
            case AUTO -> LaunchMode.current().isDevOrTest();
        };

        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        if (shouldAwaitProviders) {
            // a flag server outage is not an application outage: the provider stays registered
            // in ERROR, flags fall back to their defaults, and the sync client keeps reconnecting
            try {
                if (OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN.equals(domain)) {
                    api.setProviderAndWait(provider);
                } else {
                    api.setProviderAndWait(domain, provider);
                }
            } catch (OpenFeatureError e) {
                log.errorf(e, "OpenFeature provider for domain '%s' failed to initialize", domain);
            }
        } else {
            if (OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN.equals(domain)) {
                api.setProvider(provider);
            } else {
                api.setProvider(domain, provider);
            }
        }

        return provider;
    }

    private static FeatureProvider createProvider(String domain, List<String> providerNames,
            Map<String, FeatureProviderFactory> factories, OpenFeatureProviders registry) {
        List<FeatureProvider> providers = new ArrayList<>(providerNames.size());
        for (String providerName : providerNames) {
            FeatureProviderFactory factory = factories.get(providerName);
            if (factory == null) {
                throw new IllegalStateException("No OpenFeature provider factory for \"" + providerName + "\"");
            }
            providers.add(factory.createProvider(domain));
        }
        registry.put(domain, providers);

        if (providers.size() == 1) {
            return providers.get(0);
        }
        return new MultiProvider(providers);
    }

    /**
     * Returns the OpenFeature client for the given {@code domain}.
     */
    public static Client createClient(String domain) {
        if (OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN.equals(domain)) {
            return OpenFeatureAPI.getInstance().getClient();
        }
        return OpenFeatureAPI.getInstance().getClient(domain);
    }
}
