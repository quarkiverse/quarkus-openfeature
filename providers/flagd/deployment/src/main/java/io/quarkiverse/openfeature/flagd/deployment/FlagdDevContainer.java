package io.quarkiverse.openfeature.flagd.deployment;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import io.quarkus.deployment.builditem.Startable;

class FlagdDevContainer extends GenericContainer<FlagdDevContainer> implements Startable {
    static final int SYNC_PORT = 8015;

    static final String DEFAULT_FLAG_SOURCE = "flags.json";

    private final OptionalInt fixedExposedPort;

    FlagdDevContainer(FlagdBuildTimeConfig.DevServicesConfig config, Optional<Path> path) {
        super(DockerImageName.parse(config.imageName()));
        this.fixedExposedPort = config.port();

        if (path.isPresent()) {
            withFileSystemBind(path.get().toAbsolutePath().toString(), "/flags.json", BindMode.READ_ONLY);
            withCommand("start", "-f", "file:/flags.json");
        } else {
            withCommand("start", "-f", "file:/dev/null");
        }

        waitingFor(Wait.forLogMessage(".*starting flag sync service.*", 1));
    }

    @Override
    protected void configure() {
        super.configure();
        if (fixedExposedPort.isPresent()) {
            addFixedExposedPort(fixedExposedPort.getAsInt(), SYNC_PORT);
        } else {
            addExposedPort(SYNC_PORT);
        }
    }

    @Override
    public String getConnectionInfo() {
        return getHost() + ":" + getMappedPort(SYNC_PORT);
    }

    @Override
    public void close() {
        super.close();
    }
}
