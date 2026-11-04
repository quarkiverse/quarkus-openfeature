package io.quarkiverse.openfeature.unleash.runtime;

import java.util.Map;

import io.getunleash.engine.UnleashEngine;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkus.credentials.CredentialsProvider;
import io.quarkus.credentials.runtime.CredentialsProviderFinder;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;

public final class UnleashSetup {
    public static final String NAME = "unleash";

    public static FeatureProviderFactory createFactory(UnleashConfig config, Vertx vertx, TlsConfigurationRegistry tls) {
        return domain -> {
            UnleashConfig.DomainConfig domainConfig = config.domains().get(domain);
            if (domainConfig == null) {
                throw new IllegalStateException("No Unleash configuration found for domain: " + domain);
            }
            UnleashConfig.ProviderConfig connectionConfig = domainConfig.unleash();

            UnleashEngine engine = new UnleashEngine();

            return new UnleashFeatureProvider(engine, vertx, connectionConfig,
                    tls, getApiKey(connectionConfig),
                    connectionConfig.appName().orElse(null),
                    connectionConfig.environment().orElse(null));
        };
    }

    private static String getApiKey(UnleashConfig.ProviderConfig config) {
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
                throw new IllegalStateException("Unleash credentials-provider is set to '" + credentialsName
                        + "' but that provider returned no '" + CredentialsProvider.PASSWORD_PROPERTY_NAME
                        + "' entry");
            }
            return token;
        }

        return null;
    }
}
