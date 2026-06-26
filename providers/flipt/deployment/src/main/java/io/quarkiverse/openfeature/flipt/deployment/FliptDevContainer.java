package io.quarkiverse.openfeature.flipt.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import io.quarkus.deployment.builditem.Startable;

class FliptDevContainer extends GenericContainer<FliptDevContainer> implements Startable {
    static final int HTTP_PORT = 8080;

    static final String DEFAULT_FLAG_SOURCE = "features.yaml";

    private final OptionalInt fixedExposedPort;
    private final Optional<Path> flagsPath;

    FliptDevContainer(FliptBuildTimeConfig.DevServicesConfig config, Optional<Path> flagsPath) {
        super(DockerImageName.parse(config.imageName()));
        this.fixedExposedPort = config.port();
        this.flagsPath = flagsPath;

        waitingFor(Wait.forHttp("/").forPort(HTTP_PORT).forStatusCode(200));
    }

    @Override
    protected void configure() {
        super.configure();
        if (fixedExposedPort.isPresent()) {
            addFixedExposedPort(fixedExposedPort.getAsInt(), HTTP_PORT);
        } else {
            addExposedPort(HTTP_PORT);
        }
    }

    @Override
    public void start() {
        super.start();
        if (flagsPath.isPresent()) {
            importFeatures(flagsPath.get());
        }
    }

    @Override
    public String getConnectionInfo() {
        return "http://" + getHost() + ":" + getMappedPort(HTTP_PORT);
    }

    @Override
    public void close() {
        super.close();
    }

    private void importFeatures(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            FliptImporter.run(getConnectionInfo(), in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read features file: " + path, e);
        }
    }
}
