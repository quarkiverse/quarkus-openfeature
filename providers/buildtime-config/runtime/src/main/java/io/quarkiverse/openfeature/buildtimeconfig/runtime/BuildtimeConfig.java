package io.quarkiverse.openfeature.buildtimeconfig.runtime;

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
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public interface BuildtimeConfig {
    /**
     * Per-domain build-time flag configuration.
     */
    @ConfigDocMapKey("domain-name")
    @WithParentName
    @WithDefaults
    @WithUnnamedKey(OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN)
    Map<String, DomainBuildtimeConfig> domains();

    interface DomainBuildtimeConfig {
        /**
         * Flag values fixed at build time, keyed by flag name.
         */
        @ConfigDocMapKey("flag-name")
        Map<String, String> buildtime();
    }
}
