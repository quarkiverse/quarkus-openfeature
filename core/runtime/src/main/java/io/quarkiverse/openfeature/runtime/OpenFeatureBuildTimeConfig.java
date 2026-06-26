package io.quarkiverse.openfeature.runtime;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefaults;
import io.smallrye.config.WithParentName;
import io.smallrye.config.WithUnnamedKey;

@ConfigMapping(prefix = "quarkus.openfeature")
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public interface OpenFeatureBuildTimeConfig {
    String DEFAULT_DOMAIN = "<default>";

    /**
     * Per-domain configuration.
     */
    @ConfigDocMapKey("domain-name")
    @WithParentName
    @WithDefaults
    @WithUnnamedKey(DEFAULT_DOMAIN)
    Map<String, DomainBuildTimeConfig> domains();

    interface DomainBuildTimeConfig {
        /**
         * Which provider(s) to use for this domain.
         */
        Optional<List<String>> provider();
    }
}
