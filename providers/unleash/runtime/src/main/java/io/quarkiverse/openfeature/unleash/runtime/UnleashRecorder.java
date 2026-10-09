package io.quarkiverse.openfeature.unleash.runtime;

import java.util.Map;
import java.util.function.Supplier;

import io.getunleash.engine.UnleashEngine;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkus.credentials.CredentialsProvider;
import io.quarkus.credentials.runtime.CredentialsProviderFinder;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;

@Recorder
public class UnleashRecorder {
    public static final String NAME = "unleash";

    private final RuntimeValue<UnleashConfig> config;

    public UnleashRecorder(RuntimeValue<UnleashConfig> config) {
        this.config = config;
    }

    public FeatureProviderFactory createFactory(RuntimeValue<Vertx> vertx,
            Supplier<TlsConfigurationRegistry> tlsRegistry) {
        return domain -> {
            UnleashConfig.DomainConfig domainConfig = config.getValue().domains().get(domain);
            if (domainConfig == null) {
                throw new IllegalStateException("No Unleash configuration found for domain: " + domain);
            }
            UnleashConfig.ProviderConfig connectionConfig = domainConfig.unleash();

            UnleashEngine engine = new UnleashEngine();

            return new UnleashFeatureProvider(engine, vertx.getValue(), connectionConfig,
                    tlsRegistry.get(), getApiKey(connectionConfig),
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
