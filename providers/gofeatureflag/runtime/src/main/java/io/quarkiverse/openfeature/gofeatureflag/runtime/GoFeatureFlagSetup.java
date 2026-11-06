package io.quarkiverse.openfeature.gofeatureflag.runtime;

import java.util.Map;

import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkus.arc.ArcContainer;
import io.quarkus.credentials.CredentialsProvider;
import io.quarkus.credentials.runtime.CredentialsProviderFinder;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;
import tools.jackson.databind.ObjectMapper;

public final class GoFeatureFlagSetup {
    public static final String NAME = "gofeatureflag";

    public static FeatureProviderFactory createFactory(GoFeatureFlagConfig config, Vertx vertx,
            TlsConfigurationRegistry tls, ArcContainer arc) {
        return domain -> {
            GoFeatureFlagConfig.DomainConfig domainConfig = config.domains().get(domain);
            if (domainConfig == null) {
                throw new IllegalStateException("No GO Feature Flag configuration found for domain: " + domain);
            }
            GoFeatureFlagConfig.ProviderConfig connectionConfig = domainConfig.gofeatureflag();

            String apiKey = getApiKey(connectionConfig);

            ObjectMapper mapper = connectionConfig.useQuarkusJackson()
                    ? arc.instance(ObjectMapper.class).get()
                    : new ObjectMapper();

            int poolSize = connectionConfig.wasmInstances();
            GoFeatureFlagWasmEnginePool enginePool = new GoFeatureFlagWasmEnginePool(poolSize);

            return new GoFeatureFlagFeatureProvider(mapper, enginePool, vertx,
                    connectionConfig, tls, apiKey);
        };
    }

    private static String getApiKey(GoFeatureFlagConfig.ProviderConfig config) {
        if (config.apiKey().isPresent()) {
            return config.apiKey().get();
        }

        if (config.credentialsProvider().isPresent()) {
            String credentialsName = config.credentialsProvider().get();
            CredentialsProvider provider = CredentialsProviderFinder.find(
                    config.credentialsProviderName().orElse(null));
            Map<String, String> credentials = provider.getCredentials(credentialsName);
            String token = credentials.get(CredentialsProvider.PASSWORD_PROPERTY_NAME);
            // falling back to an unauthenticated connection here would hide the
            // misconfiguration: these credentials were asked for by name
            if (token == null || token.isEmpty()) {
                throw new IllegalStateException("GO Feature Flag credentials-provider is set to '" + credentialsName
                        + "' but that provider returned no '" + CredentialsProvider.PASSWORD_PROPERTY_NAME
                        + "' entry");
            }
            return token;
        }

        return null;
    }
}
