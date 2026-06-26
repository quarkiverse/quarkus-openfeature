package io.quarkiverse.openfeature.test.runtime;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

public class UnleashTestContainer extends GenericContainer<UnleashTestContainer> {
    public static final int HTTP_PORT = 4242;

    public static final String API_TOKEN = "default:development.api-token-for-testing";

    private final Network network;
    private final GenericContainer<?> postgres;

    /**
     * @param flagsResource classpath resource imported on startup via {@code IMPORT_FILE},
     *        or {@code null} to start with no flags
     */
    public UnleashTestContainer(String flagsResource) {
        super(DockerImageName.parse("unleashorg/unleash-server:latest"));

        network = Network.newNetwork();

        postgres = new GenericContainer<>(DockerImageName.parse("postgres:18"))
                .withNetwork(network)
                .withNetworkAliases("unleash-db")
                .withExposedPorts(5432)
                .withEnv("POSTGRES_DB", "unleash")
                .withEnv("POSTGRES_USER", "unleash")
                .withEnv("POSTGRES_PASSWORD", "unleash")
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 1));

        withNetwork(network);
        withExposedPorts(HTTP_PORT);
        withEnv("DATABASE_URL", "postgres://unleash:unleash@unleash-db:5432/unleash");
        withEnv("DATABASE_SSL", "false");
        withEnv("INIT_BACKEND_API_TOKENS", API_TOKEN);
        withEnv("CHECK_VERSION", "false");
        waitingFor(Wait.forHttp("/health").forPort(HTTP_PORT).forStatusCode(200));

        if (flagsResource != null) {
            withCopyFileToContainer(MountableFile.forClasspathResource(flagsResource), "/tmp/unleash.json");
            withEnv("IMPORT_FILE", "/tmp/unleash.json");
            withEnv("IMPORT_PROJECT", "default");
            withEnv("IMPORT_ENVIRONMENT", "development");
        }
    }

    @Override
    public void start() {
        postgres.start();
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        postgres.stop();
        network.close();
    }

    public String getConnectionInfo() {
        return "http://" + getHost() + ":" + getMappedPort(HTTP_PORT) + "/api";
    }
}
