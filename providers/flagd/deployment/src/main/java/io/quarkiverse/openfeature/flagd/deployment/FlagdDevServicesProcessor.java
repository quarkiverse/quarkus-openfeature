package io.quarkiverse.openfeature.flagd.deployment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jboss.logging.Logger;

import io.quarkiverse.openfeature.flagd.runtime.FlagdRecorder;
import io.quarkiverse.openfeature.runtime.OpenFeatureBuildTimeConfig;
import io.quarkiverse.openfeature.runtime.OpenFeatureBuildTimeConfig.DomainBuildTimeConfig;
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
class FlagdDevServicesProcessor {
    private static final Logger log = Logger.getLogger(FlagdDevServicesProcessor.class);

    @BuildStep
    void watchFlagSources(FlagdBuildTimeConfig config,
            OpenFeatureBuildTimeConfig openFeatureConfig,
            BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles) {
        Set<String> paths = new HashSet<>();
        for (Map.Entry<String, DomainBuildTimeConfig> entry : allDomains(openFeatureConfig).entrySet()) {
            if (!domainUsesProvider(entry.getValue())) {
                continue;
            }
            FlagdBuildTimeConfig.DevServicesConfig devServicesConfig = getDevServicesConfig(config, entry.getKey());
            paths.add(devServicesConfig.path().orElse(FlagdDevContainer.DEFAULT_FLAG_SOURCE));
        }
        for (String path : paths) {
            watchedFiles.produce(new HotDeploymentWatchedFileBuildItem(path));
        }
    }

    @BuildStep
    void startFlagd(FlagdBuildTimeConfig config,
            OpenFeatureBuildTimeConfig openFeatureConfig,
            DockerStatusBuildItem dockerStatusBuildItem,
            ArchiveRootBuildItem archiveRoot,
            BuildProducer<DevServicesResultBuildItem> devServices) {
        for (Map.Entry<String, DomainBuildTimeConfig> entry : allDomains(openFeatureConfig).entrySet()) {
            String domain = entry.getKey();
            if (!domainUsesProvider(entry.getValue())) {
                continue;
            }

            FlagdBuildTimeConfig.DevServicesConfig devServicesConfig = getDevServicesConfig(config, domain);

            if (!devServicesConfig.enabled()) {
                log.debugf("Not starting Dev Services for flagd (%s) as it has been disabled in the config",
                        domainLabel(domain));
                continue;
            }

            String urlConfigKey = configKey(domain, "flagd.url");
            if (ConfigUtils.isPropertyNonEmpty(urlConfigKey)) {
                log.debugf("Not starting Dev Services for flagd (%s) as a URL has been provided",
                        domainLabel(domain));
                continue;
            }

            if (!dockerStatusBuildItem.isContainerRuntimeAvailable()) {
                log.warnf("Please configure %s or get a working Docker instance", urlConfigKey);
                continue;
            }

            Optional<Path> path = resolvePath(domain, devServicesConfig, archiveRoot);

            devServices.produce(DevServicesResultBuildItem.owned()
                    .feature("openfeature-flagd")
                    .serviceName(domain)
                    .serviceConfig(domain + ";" + devServicesConfig.hashCode())
                    .startable(() -> new FlagdDevContainer(devServicesConfig, path))
                    .configProvider(Map.of(urlConfigKey, s -> s.getConnectionInfo()))
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
                || domainConfig.provider().get().contains(FlagdRecorder.NAME);
    }

    private static FlagdBuildTimeConfig.DevServicesConfig getDevServicesConfig(FlagdBuildTimeConfig config, String domain) {
        FlagdBuildTimeConfig.DomainConfig domainConfig = config.domains().get(domain);
        if (domainConfig == null) {
            domainConfig = config.domains().get(OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN);
        }
        return domainConfig.flagd().devservices();
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

    private Optional<Path> resolvePath(String domain, FlagdBuildTimeConfig.DevServicesConfig config,
            ArchiveRootBuildItem archiveRoot) {
        if (config.path().isPresent()) {
            String name = config.path().get();
            Path path = findInArchiveRoot(archiveRoot, name);
            if (path == null) {
                throw new IllegalStateException("Configured flag source path not found for "
                        + domainLabel(domain) + ": " + name);
            }
            return Optional.of(path);
        }

        Path defaultPath = findInArchiveRoot(archiveRoot, FlagdDevContainer.DEFAULT_FLAG_SOURCE);
        if (defaultPath != null) {
            return Optional.of(defaultPath);
        }

        log.infof("No %s found, starting flagd with empty configuration for %s",
                FlagdDevContainer.DEFAULT_FLAG_SOURCE, domainLabel(domain));
        return Optional.empty();
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
