package io.quarkiverse.openfeature.flagd.runtime;

import dev.openfeature.contrib.tools.flagd.core.FlagdCore;
import io.quarkiverse.openfeature.runtime.FeatureProviderFactory;
import io.quarkus.arc.ArcContainer;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;
import tools.jackson.databind.ObjectMapper;

public final class FlagdSetup {
    public static final String NAME = "flagd";

    public static FeatureProviderFactory createFactory(FlagdConfig config, Vertx vertx,
            TlsConfigurationRegistry tls, ArcContainer arc) {
        return domain -> {
            FlagdConfig.DomainConfig domainConfig = config.domains().get(domain);
            if (domainConfig == null) {
                throw new IllegalStateException("No flagd configuration found for domain: " + domain);
            }
            FlagdConfig.ProviderConfig connectionConfig = domainConfig.flagd();
            ObjectMapper mapper = connectionConfig.useQuarkusJackson()
                    ? arc.instance(ObjectMapper.class).get()
                    : new ObjectMapper();
            FlagdCore evaluator = new FlagdCore();
            return new FlagdFeatureProvider(mapper, evaluator, vertx, connectionConfig, tls);
        };
    }
}
