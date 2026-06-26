package io.quarkiverse.openfeature.runtime;

import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import dev.openfeature.sdk.OpenFeatureAPI;
import io.quarkus.runtime.Startup;

@Singleton
public class OpenFeatureProducer {
    @Produces
    @Singleton
    @Startup
    OpenFeatureAPI openFeatureApi() {
        return OpenFeatureAPI.getInstance();
    }

    void shutdown(@Disposes OpenFeatureAPI api) {
        api.shutdown();
    }
}
