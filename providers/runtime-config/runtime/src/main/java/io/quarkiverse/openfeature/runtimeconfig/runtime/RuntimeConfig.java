package io.quarkiverse.openfeature.runtimeconfig.runtime;

import java.util.Map;

import io.quarkiverse.openfeature.runtime.OpenFeatureBuildTimeConfig;
import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefaults;
import io.smallrye.config.WithParentName;
import io.smallrye.config.WithUnnamedKey;

@ConfigMapping(prefix = "quarkus.openfeature")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface RuntimeConfig {
    /**
     * Per-domain runtime flag configuration.
     */
    @ConfigDocMapKey("domain-name")
    @WithParentName
    @WithDefaults
    @WithUnnamedKey(OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN)
    Map<String, DomainRuntimeConfig> domains();

    interface DomainRuntimeConfig {
        /**
         * Flag values configurable at runtime, keyed by flag name.
         */
        @ConfigDocMapKey("flag-name")
        Map<String, String> runtime();
    }
}
