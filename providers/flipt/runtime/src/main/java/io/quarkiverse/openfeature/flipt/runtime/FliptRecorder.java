package io.quarkiverse.openfeature.flipt.runtime;

import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkus.arc.Arc;
import io.quarkus.credentials.CredentialsProvider;
import io.quarkus.credentials.runtime.CredentialsProviderFinder;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;

@Recorder
public class FliptRecorder {
    public static final String NAME = "flipt";

    private final RuntimeValue<FliptConfig> config;

    public FliptRecorder(RuntimeValue<FliptConfig> config) {
        this.config = config;
    }

    public FeatureProviderFactory createFactory(RuntimeValue<Vertx> vertx,
            Supplier<TlsConfigurationRegistry> tlsRegistry) {
        return domain -> {
            FliptConfig.DomainConfig domainConfig = config.getValue().domains().get(domain);
            if (domainConfig == null) {
                throw new IllegalStateException("No Flipt configuration found for domain: " + domain);
            }
            FliptConfig.ProviderConfig connectionConfig = domainConfig.flipt();

            String authHeader = getAuthHeader(connectionConfig);

            ObjectMapper mapper = connectionConfig.useQuarkusJackson()
                    ? Arc.requireContainer().instance(ObjectMapper.class).get()
                    : new ObjectMapper();

            ObjectNode emptySnapshot = mapper.createObjectNode();
            emptySnapshot.putObject("namespace").put("key", connectionConfig.namespace());
            emptySnapshot.putArray("flags");
            int poolSize = connectionConfig.wasmInstances();
            FliptWasmEnginePool enginePool = new FliptWasmEnginePool(poolSize, connectionConfig.namespace(),
                    emptySnapshot.toString(), mapper);

            return new FliptFeatureProvider(mapper, enginePool, vertx.getValue(), connectionConfig,
                    tlsRegistry.get(), authHeader);
        };
    }

    private static String getAuthHeader(FliptConfig.ProviderConfig config) {
        if (config.authType() == FliptConfig.AuthType.NONE) {
            return null;
        }

        String token = null;

        if (config.authToken().isPresent()) {
            token = config.authToken().get();
        } else if (config.credentialsProvider().isPresent()) {
            CredentialsProvider provider = CredentialsProviderFinder.find(config.credentialsProviderName().orElse(null));
            Map<String, String> credentials = provider.getCredentials(config.credentialsProvider().get());
            token = credentials.get(CredentialsProvider.PASSWORD_PROPERTY_NAME);
        }

        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("Flipt authentication type is " + config.authType()
                    + " but no token was provided");
        }

        return "Bearer " + token;
    }
}
