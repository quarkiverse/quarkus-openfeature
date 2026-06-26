package io.quarkiverse.openfeature.gofeatureflag.runtime;

import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkus.arc.Arc;
import io.quarkus.credentials.CredentialsProvider;
import io.quarkus.credentials.runtime.CredentialsProviderFinder;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;

@Recorder
public class GoFeatureFlagRecorder {
    public static final String NAME = "gofeatureflag";

    private final RuntimeValue<GoFeatureFlagConfig> config;

    public GoFeatureFlagRecorder(RuntimeValue<GoFeatureFlagConfig> config) {
        this.config = config;
    }

    public FeatureProviderFactory createFactory(RuntimeValue<Vertx> vertx,
            Supplier<TlsConfigurationRegistry> tlsRegistry) {
        return domain -> {
            GoFeatureFlagConfig.DomainConfig domainConfig = config.getValue().domains().get(domain);
            if (domainConfig == null) {
                throw new IllegalStateException("No GO Feature Flag configuration found for domain: " + domain);
            }
            GoFeatureFlagConfig.ProviderConfig connectionConfig = domainConfig.gofeatureflag();

            String apiKey = getApiKey(connectionConfig);

            ObjectMapper mapper = connectionConfig.useQuarkusJackson()
                    ? Arc.requireContainer().instance(ObjectMapper.class).get()
                    : new ObjectMapper();

            int poolSize = connectionConfig.wasmInstances();
            GoFeatureFlagWasmEnginePool enginePool = new GoFeatureFlagWasmEnginePool(poolSize);

            return new GoFeatureFlagFeatureProvider(mapper, enginePool, vertx.getValue(),
                    connectionConfig, tlsRegistry.get(), apiKey);
        };
    }

    private static String getApiKey(GoFeatureFlagConfig.ProviderConfig config) {
        if (config.apiKey().isPresent()) {
            return config.apiKey().get();
        }

        if (config.credentialsProvider().isPresent()) {
            CredentialsProvider provider = CredentialsProviderFinder.find(
                    config.credentialsProviderName().orElse(null));
            Map<String, String> credentials = provider.getCredentials(config.credentialsProvider().get());
            String token = credentials.get(CredentialsProvider.PASSWORD_PROPERTY_NAME);
            if (token != null && !token.isEmpty()) {
                return token;
            }
        }

        return null;
    }
}
