package io.quarkiverse.openfeature.gofeatureflag.deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import io.quarkus.deployment.builditem.Startable;

class GoFeatureFlagDevContainer extends GenericContainer<GoFeatureFlagDevContainer> implements Startable {
    static final int HTTP_PORT = 1031;

    static final String DEFAULT_FLAG_SOURCE = "flags.goff.yaml";

    private final OptionalInt fixedExposedPort;
    private final Optional<Path> flagsPath;
    private Path tempConfigPath;

    GoFeatureFlagDevContainer(GoFeatureFlagBuildTimeConfig.DevServicesConfig config, Optional<Path> flagsPath) {
        super(DockerImageName.parse(config.imageName()));
        this.fixedExposedPort = config.port();
        this.flagsPath = flagsPath;

        waitingFor(Wait.forHttp("/health").forPort(HTTP_PORT).forStatusCode(200));
    }

    @Override
    protected void configure() {
        super.configure();
        if (fixedExposedPort.isPresent()) {
            addFixedExposedPort(fixedExposedPort.getAsInt(), HTTP_PORT);
        } else {
            addExposedPort(HTTP_PORT);
        }

        String proxyConfig = buildProxyConfig();
        try {
            tempConfigPath = Files.createTempFile("goff-proxy", ".yaml");
            Files.writeString(tempConfigPath, proxyConfig);
            withCopyFileToContainer(MountableFile.forHostPath(tempConfigPath), "/goff/goff-proxy.yaml");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create GO Feature Flag proxy configuration", e);
        }

        withCommand("/go-feature-flag", "--config", "/goff/goff-proxy.yaml");

        if (flagsPath.isPresent()) {
            withFileSystemBind(flagsPath.get().toAbsolutePath().toString(), "/goff/flags.goff.yaml", BindMode.READ_ONLY);
        }
    }

    @Override
    public String getConnectionInfo() {
        return "http://" + getHost() + ":" + getMappedPort(HTTP_PORT);
    }

    @Override
    public void close() {
        super.close();
        if (tempConfigPath != null) {
            try {
                Files.deleteIfExists(tempConfigPath);
            } catch (IOException ignored) {
            }
        }
    }

    private String buildProxyConfig() {
        StringBuilder result = new StringBuilder();
        result.append("pollingInterval: 1000\n");
        if (flagsPath.isPresent()) {
            result.append("retrievers:\n");
            result.append("  - kind: file\n");
            result.append("    path: /goff/flags.goff.yaml\n");
        }
        return result.toString();
    }
}
