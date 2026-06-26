package io.quarkiverse.openfeature.unleash.runtime;

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
public interface UnleashConfig {
    /**
     * Per-domain Unleash configuration.
     */
    @ConfigDocMapKey("domain-name")
    @WithParentName
    @WithDefaults
    @WithUnnamedKey(OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN)
    Map<String, DomainConfig> domains();

    interface DomainConfig {
        /**
         * Unleash configuration.
         */
        ProviderConfig unleash();
    }

    interface ProviderConfig {
        /**
         * Unleash API URL.
         */
        @WithDefault("http://localhost:4242/api")
        String url();

        /**
         * Backend token for authentication with Unleash.
         */
        Optional<String> apiKey();

        /**
         * Interval between synchronization polls. When a successful poll finishes,
         * the next poll is scheduled in this interval. In case the poll is unsuccessful,
         * retry happens on an internally managed schedule.
         */
        @WithDefault("1m")
        Duration pollInterval();

        /**
         * The credentials provider name. This is the key used to look up
         * credentials in the credentials provider.
         */
        Optional<String> credentialsProvider();

        /**
         * The credentials provider bean name.
         * <p>
         * This is a bean name (as in {@code @Named}) of a bean that implements
         * {@code CredentialsProvider}. It is used to select the credentials
         * provider bean when multiple exist. This is unnecessary when there
         * is only one credentials provider available.
         * <p>
         * For Vault, the credentials provider bean name is
         * {@code vault-credentials-provider}.
         */
        Optional<String> credentialsProviderName();

        /**
         * TLS configuration name from the Quarkus TLS registry.
         */
        Optional<String> tlsConfigurationName();

        /**
         * Grace period before transitioning from STALE to ERROR
         * after a connection loss. During this period, the provider continues
         * serving cached flag values while attempting to reconnect.
         * If the connection is restored within the grace period, the provider
         * transitions back to READY without emitting an ERROR event.
         */
        @WithDefault("1m")
        Duration gracePeriod();

        /**
         * Application name passed to the Unleash context for evaluation.
         */
        @WithDefault("${quarkus.application.name}")
        Optional<String> appName();

        /**
         * Environment passed to the Unleash context for evaluation.
         */
        Optional<String> environment();
    }
}
