package io.quarkiverse.openfeature.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.multiprovider.MultiProvider;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class OpenFeatureRecorder {
    private static final Map<String, List<FeatureProvider>> providers = new ConcurrentHashMap<>();

    public static List<FeatureProvider> getProviders(String domain) {
        return providers.getOrDefault(domain, Collections.emptyList());
    }

    public void reset() {
        providers.clear();
    }

    public void configureProvider(String domain, List<FeatureProviderFactory> factories) {
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        FeatureProvider provider = createProvider(domain, factories);

        if (OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN.equals(domain)) {
            api.setProviderAndWait(provider);
        } else {
            api.setProviderAndWait(domain, provider);
        }
    }

    private FeatureProvider createProvider(String domain, List<FeatureProviderFactory> factories) {
        List<FeatureProvider> providers = new ArrayList<>(factories.size());
        for (FeatureProviderFactory factory : factories) {
            providers.add(factory.createProvider(domain));
        }
        OpenFeatureRecorder.providers.put(domain, List.copyOf(providers));

        if (providers.size() == 1) {
            return providers.get(0);
        }
        return new MultiProvider(providers);
    }

    public Supplier<Client> createDefaultClient() {
        return () -> OpenFeatureAPI.getInstance().getClient();
    }

    public Supplier<Client> createClient(String domain) {
        return () -> OpenFeatureAPI.getInstance().getClient(domain);
    }
}
