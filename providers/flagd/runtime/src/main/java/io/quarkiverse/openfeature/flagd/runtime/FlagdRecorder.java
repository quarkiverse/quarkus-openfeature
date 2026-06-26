package io.quarkiverse.openfeature.flagd.runtime;

import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.openfeature.contrib.tools.flagd.core.FlagdCore;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;

@Recorder
public class FlagdRecorder {
    public static final String NAME = "flagd";

    private final RuntimeValue<FlagdConfig> config;

    public FlagdRecorder(RuntimeValue<FlagdConfig> config) {
        this.config = config;
    }

    public FeatureProviderFactory createFactory(RuntimeValue<Vertx> vertx,
            Supplier<TlsConfigurationRegistry> tlsRegistry) {
        return domain -> {
            FlagdConfig.DomainConfig domainConfig = config.getValue().domains().get(domain);
            if (domainConfig == null) {
                throw new IllegalStateException("No flagd configuration found for domain: " + domain);
            }
            FlagdConfig.ProviderConfig connectionConfig = domainConfig.flagd();
            ObjectMapper mapper = connectionConfig.useQuarkusJackson()
                    ? Arc.requireContainer().instance(ObjectMapper.class).get()
                    : new ObjectMapper();
            FlagdCore evaluator = new FlagdCore();
            return new FlagdFeatureProvider(mapper, evaluator, vertx.getValue(), connectionConfig, tlsRegistry.get());
        };
    }
}
