package io.quarkiverse.openfeature.flipt.runtime;

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
public interface FliptConfig {
    /**
     * Per-domain Flipt configuration.
     */
    @ConfigDocMapKey("domain-name")
    @WithParentName
    @WithDefaults
    @WithUnnamedKey(OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN)
    Map<String, DomainConfig> domains();

    interface DomainConfig {
        /**
         * Flipt configuration.
         */
        ProviderConfig flipt();
    }

    interface ProviderConfig {
        /**
         * Flipt server URL.
         */
        @WithDefault("http://localhost:8080")
        String url();

        /**
         * Flipt environment.
         */
        @WithDefault("default")
        String environment();

        /**
         * Flipt namespace.
         */
        @WithDefault("default")
        String namespace();

        /**
         * Authentication type.
         */
        @WithDefault("none")
        AuthType authType();

        /**
         * Authentication token value. For development and testing only.
         * For production, use a credentials provider instead.
         */
        Optional<String> authToken();

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
         * Git reference for versioned flag state. When set,
         * Flipt returns flags at this specific reference.
         */
        Optional<String> reference();

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
         * Number of WASM engine instances in the evaluation pool.
         * Each engine can evaluate one flag at a time, so this controls
         * the maximum concurrency of flag evaluations.
         */
        @WithDefault("16")
        int wasmInstances();

        /**
         * Whether to use the Quarkus-managed {@code ObjectMapper} from the {@code quarkus-jackson}
         * extension. When {@code false}, the provider creates its own {@code ObjectMapper} instance.
         */
        @WithDefault("true")
        boolean useQuarkusJackson();
    }

    enum AuthType {
        NONE,
        CLIENT_TOKEN
    }
}
