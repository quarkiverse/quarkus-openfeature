package io.quarkiverse.openfeature.flipt.deployment;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import io.quarkiverse.openfeature.runtime.OpenFeatureBuildTimeConfig;
import io.quarkus.runtime.annotations.ConfigDocDefault;
import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithDefaults;
import io.smallrye.config.WithParentName;
import io.smallrye.config.WithUnnamedKey;

@ConfigMapping(prefix = "quarkus.openfeature")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface FliptBuildTimeConfig {
    /**
     * Per-domain Flipt build-time configuration.
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
         * Dev Services.
         */
        DevServicesConfig devservices();
    }

    interface DevServicesConfig {
        /**
         * If DevServices has been explicitly enabled or disabled. DevServices is generally enabled
         * by default, unless there is an existing configuration present.
         * <p>
         * When DevServices is enabled Quarkus will attempt to automatically configure and start
         * a Flipt instance when running in Dev or Test mode and when Docker is running.
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * The container image name to use, for container based DevServices providers.
         */
        @WithDefault("flipt/flipt:v2")
        String imageName();

        /**
         * Optional fixed port the dev service will listen to.
         * <p>
         * If not defined, the port will be chosen randomly.
         */
        OptionalInt port();

        /**
         * Path to a flag definition file on the classpath.
         * If not configured explicitly and the default {@code features.yaml}
         * file does not exist, Flipt starts with an empty flag configuration.
         * If configured explicitly and the file does not exist,
         * the build fails.
         */
        @ConfigDocDefault("features.yaml")
        Optional<String> path();

        /**
         * Indicates if the Flipt instance managed by Quarkus Dev Services is shared.
         * When shared, Quarkus looks for running containers using label-based service discovery.
         * If a matching container is found, it is used, and so a second one is not started.
         * Otherwise, Dev Services for Flipt starts a new container.
         * <p>
         * The discovery uses the {@code quarkus-dev-service-flipt} label.
         * The value is configured using the {@code service-name} property.
         * <p>
         * Container sharing is only used in dev mode.
         */
        @WithDefault("true")
        boolean shared();

        /**
         * The value of the {@code quarkus-dev-service-flipt} label attached to the started container.
         * This property is used when {@code shared} is set to {@code true}.
         * In this case, before starting a container, Dev Services for Flipt looks for a container with the
         * {@code quarkus-dev-service-flipt} label
         * set to the configured value. If found, it will use this container instead of starting a new one. Otherwise, it
         * starts a new container with the {@code quarkus-dev-service-flipt} label set to the specified value.
         * <p>
         * This property is used when you need multiple shared Flipt servers.
         */
        @WithDefault("flipt")
        String serviceName();
    }
}
