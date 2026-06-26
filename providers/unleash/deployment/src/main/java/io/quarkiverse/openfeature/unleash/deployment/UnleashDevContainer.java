package io.quarkiverse.openfeature.unleash.deployment;

import java.util.Optional;
import java.util.OptionalInt;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import io.quarkus.deployment.builditem.Startable;

class UnleashDevContainer extends GenericContainer<UnleashDevContainer> implements Startable {
    static final int HTTP_PORT = 4242;

    static final String API_TOKEN = "default:development.api-token-for-testing";

    static final String DEFAULT_FLAG_SOURCE = "unleash.json";

    private final OptionalInt fixedExposedPort;
    private final Optional<String> flagsPath;

    private Network network;
    private GenericContainer<?> postgres;

    UnleashDevContainer(UnleashBuildTimeConfig.DevServicesConfig config, Optional<String> flagsPath) {
        super(DockerImageName.parse(config.imageName()));
        this.fixedExposedPort = config.port();
        this.flagsPath = flagsPath;
    }

    @Override
    public void start() {
        network = Network.newNetwork();

        postgres = new GenericContainer<>(DockerImageName.parse("postgres:18"))
                .withNetwork(network)
                .withNetworkAliases("unleash-db")
                .withExposedPorts(5432)
                .withEnv("POSTGRES_DB", "unleash")
                .withEnv("POSTGRES_USER", "unleash")
                .withEnv("POSTGRES_PASSWORD", "unleash")
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 1));
        postgres.start();

        withNetwork(network);
        withEnv("DATABASE_URL", "postgres://unleash:unleash@unleash-db:5432/unleash");
        withEnv("DATABASE_SSL", "false");
        withEnv("INIT_BACKEND_API_TOKENS", API_TOKEN);
        withEnv("CHECK_VERSION", "false");
        waitingFor(Wait.forHttp("/health").forPort(HTTP_PORT).forStatusCode(200));

        if (flagsPath.isPresent()) {
            withCopyFileToContainer(MountableFile.forHostPath(flagsPath.get()), "/tmp/unleash.json");
            withEnv("IMPORT_FILE", "/tmp/unleash.json");
            withEnv("IMPORT_PROJECT", "default");
            withEnv("IMPORT_ENVIRONMENT", "development");
        }

        if (fixedExposedPort.isPresent()) {
            addFixedExposedPort(fixedExposedPort.getAsInt(), HTTP_PORT);
        } else {
            addExposedPort(HTTP_PORT);
        }

        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        postgres.stop();
        network.close();
    }

    @Override
    public void close() {
        super.close();
    }

    @Override
    public String getConnectionInfo() {
        return "http://" + getHost() + ":" + getMappedPort(HTTP_PORT) + "/api";
    }

    String getApiToken() {
        return API_TOKEN;
    }
}
