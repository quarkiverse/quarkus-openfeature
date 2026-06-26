package io.quarkiverse.openfeature.unleash.deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jboss.logging.Logger;

import io.quarkiverse.openfeature.runtime.OpenFeatureBuildTimeConfig;
import io.quarkiverse.openfeature.runtime.OpenFeatureBuildTimeConfig.DomainBuildTimeConfig;
import io.quarkiverse.openfeature.unleash.runtime.UnleashRecorder;
import io.quarkus.deployment.IsDevServicesSupportedByLaunchMode;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.ArchiveRootBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.dev.devservices.DevServicesConfig;
import io.quarkus.runtime.configuration.ConfigUtils;

@BuildSteps(onlyIf = { IsDevServicesSupportedByLaunchMode.class, DevServicesConfig.Enabled.class })
class UnleashDevServicesProcessor {
    private static final Logger log = Logger.getLogger(UnleashDevServicesProcessor.class);

    @BuildStep
    void watchFlagSources(UnleashBuildTimeConfig config,
            OpenFeatureBuildTimeConfig openFeatureConfig,
            BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles) {
        Set<String> paths = new HashSet<>();
        for (Map.Entry<String, DomainBuildTimeConfig> entry : allDomains(openFeatureConfig).entrySet()) {
            if (!domainUsesProvider(entry.getValue())) {
                continue;
            }
            UnleashBuildTimeConfig.DevServicesConfig devServicesConfig = getDevServicesConfig(config, entry.getKey());
            paths.add(devServicesConfig.path().orElse(UnleashDevContainer.DEFAULT_FLAG_SOURCE));
        }
        for (String path : paths) {
            watchedFiles.produce(new HotDeploymentWatchedFileBuildItem(path));
        }
    }

    @BuildStep
    void startUnleash(UnleashBuildTimeConfig config,
            OpenFeatureBuildTimeConfig openFeatureConfig,
            DockerStatusBuildItem dockerStatusBuildItem,
            ArchiveRootBuildItem archiveRoot,
            BuildProducer<DevServicesResultBuildItem> devServices) {
        for (Map.Entry<String, DomainBuildTimeConfig> entry : allDomains(openFeatureConfig).entrySet()) {
            String domain = entry.getKey();
            if (!domainUsesProvider(entry.getValue())) {
                continue;
            }

            UnleashBuildTimeConfig.DevServicesConfig devServicesConfig = getDevServicesConfig(config, domain);

            if (!devServicesConfig.enabled()) {
                log.debugf("Not starting Dev Services for Unleash (%s) as it has been disabled in the config",
                        domainLabel(domain));
                continue;
            }

            String urlConfigKey = configKey(domain, "unleash.url");
            if (ConfigUtils.isPropertyNonEmpty(urlConfigKey)) {
                log.debugf("Not starting Dev Services for Unleash (%s) as a URL has been provided",
                        domainLabel(domain));
                continue;
            }

            if (!dockerStatusBuildItem.isContainerRuntimeAvailable()) {
                log.warnf("Please configure %s or get a working Docker instance", urlConfigKey);
                continue;
            }

            Optional<String> path = resolvePath(domain, devServicesConfig, archiveRoot);

            String serviceConfigId = domain + ";" + Objects.hash(devServicesConfig) + ";" + fileModTimeHash(path);

            String apiKeyConfigKey = configKey(domain, "unleash.api-key");

            devServices.produce(DevServicesResultBuildItem.owned()
                    .feature("openfeature-unleash")
                    .serviceName(domain)
                    .serviceConfig(serviceConfigId)
                    .startable(() -> new UnleashDevContainer(devServicesConfig, path))
                    .configProvider(Map.of(
                            urlConfigKey, UnleashDevContainer::getConnectionInfo,
                            apiKeyConfigKey, UnleashDevContainer::getApiToken))
                    .build());
        }
    }

    private static Map<String, DomainBuildTimeConfig> allDomains(OpenFeatureBuildTimeConfig config) {
        Map<String, DomainBuildTimeConfig> result = new HashMap<>();
        result.put(OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN,
                config.domains().getOrDefault(OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN,
                        (DomainBuildTimeConfig) Optional::empty));
        for (Map.Entry<String, DomainBuildTimeConfig> entry : config.domains().entrySet()) {
            if (!OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN.equals(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static boolean domainUsesProvider(DomainBuildTimeConfig domainConfig) {
        return domainConfig.provider().isEmpty()
                || domainConfig.provider().get().contains(UnleashRecorder.NAME);
    }

    private static UnleashBuildTimeConfig.DevServicesConfig getDevServicesConfig(UnleashBuildTimeConfig config, String domain) {
        UnleashBuildTimeConfig.DomainConfig domainConfig = config.domains().get(domain);
        if (domainConfig == null) {
            domainConfig = config.domains().get(OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN);
        }
        return domainConfig.unleash().devservices();
    }

    private static String configKey(String domain, String attribute) {
        if (OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN.equals(domain)) {
            return "quarkus.openfeature." + attribute;
        }
        return "quarkus.openfeature.\"" + domain + "\"." + attribute;
    }

    private static String domainLabel(String domain) {
        if (OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN.equals(domain)) {
            return "default domain";
        }
        return "domain \"" + domain + "\"";
    }

    private Optional<String> resolvePath(String domain, UnleashBuildTimeConfig.DevServicesConfig config,
            ArchiveRootBuildItem archiveRoot) {
        if (config.path().isPresent()) {
            String name = config.path().get();
            Path path = findInArchiveRoot(archiveRoot, name);
            if (path == null) {
                throw new IllegalStateException("Configured flag source path not found for "
                        + domainLabel(domain) + ": " + name);
            }
            return Optional.of(path.toString());
        }

        Path defaultPath = findInArchiveRoot(archiveRoot, UnleashDevContainer.DEFAULT_FLAG_SOURCE);
        if (defaultPath != null) {
            return Optional.of(defaultPath.toString());
        }

        log.infof("No %s found, starting Unleash with empty configuration for %s",
                UnleashDevContainer.DEFAULT_FLAG_SOURCE, domainLabel(domain));
        return Optional.empty();
    }

    private static int fileModTimeHash(Optional<String> path) {
        if (path.isEmpty()) {
            return 0;
        }
        try {
            return Files.getLastModifiedTime(Path.of(path.get())).hashCode();
        } catch (IOException e) {
            return 0;
        }
    }

    private Path findInArchiveRoot(ArchiveRootBuildItem archiveRoot, String resource) {
        for (Path root : archiveRoot.getRootDirectories()) {
            Path candidate = root.resolve(resource);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
