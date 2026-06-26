package io.quarkiverse.openfeature.flagd.runtime;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import io.quarkiverse.openfeature.runtime.OpenFeatureBuildTimeConfig;
import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithDefaults;
import io.smallrye.config.WithParentName;
import io.smallrye.config.WithUnnamedKey;

@ConfigMapping(prefix = "quarkus.openfeature")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface FlagdConfig {
    /**
     * Per-domain flagd configuration.
     */
    @ConfigDocMapKey("domain-name")
    @WithParentName
    @WithDefaults
    @WithUnnamedKey(OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN)
    Map<String, DomainConfig> domains();

    interface DomainConfig {
        /**
         * flagd configuration.
         */
        ProviderConfig flagd();
    }

    interface ProviderConfig {
        /**
         * flagd server URL.
         */
        @WithDefault("localhost:8015")
        String url();

        /**
         * TLS configuration name from the Quarkus TLS registry.
         */
        Optional<String> tlsConfigurationName();

        /**
         * Idle timeout for the gRPC streaming connection. If no data is received within this period,
         * the stream is closed and a reconnect is attempted.
         */
        @WithDefault("10m")
        Duration streamDeadline();

        /**
         * Grace period before transitioning from STALE to ERROR after a connection loss. During this period,
         * the provider continues serving cached flag values while attempting to reconnect. If the connection
         * is restored within the grace period, the provider transitions back to READY without emitting
         * an ERROR event.
         */
        @WithDefault("1m")
        Duration gracePeriod();

        /**
         * Unique identifier for this client, sent to flagd for telemetry, monitoring, and routing purposes.
         */
        Optional<String> providerId();

        /**
         * Selector for filtering which flag source to sync when flagd serves multiple flag sources.
         */
        Optional<String> selector();

        /**
         * Path to a JSON flag definition file for offline mode. When set, the provider reads flags from
         * this file instead of connecting to a flagd instance.
         */
        Optional<String> offlinePath();

        /**
         * Whether to use the Quarkus-managed {@code ObjectMapper} from the {@code quarkus-jackson}
         * extension. When {@code false}, the provider creates its own {@code ObjectMapper} instance.
         */
        @WithDefault("true")
        boolean useQuarkusJackson();
    }
}
