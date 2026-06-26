package io.quarkiverse.openfeature.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.openfeature")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface OpenFeatureRuntimeConfig {
    /**
     * Whether to wait for providers to be ready before the application starts.
     * <p>
     * When {@code auto}, providers are awaited in the dev and test launch modes but not
     * in production. When {@code true}, startup blocks until all providers have received their
     * initial flag data. When {@code false}, providers initialize asynchronously and return
     * default values until ready.
     */
    @WithDefault("auto")
    AwaitProviders awaitProviders();

    enum AwaitProviders {
        AUTO,
        TRUE,
        FALSE,
    }
}
