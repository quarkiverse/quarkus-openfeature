package io.quarkiverse.openfeature.test.runtime;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

public class GoFeatureFlagTestContainer extends GenericContainer<GoFeatureFlagTestContainer> {
    public static final int HTTP_PORT = 1031;

    /**
     * @param configResource classpath resource mounted as {@code /goff/goff-proxy.yaml}
     */
    public GoFeatureFlagTestContainer(String configResource) {
        super("gofeatureflag/go-feature-flag:latest");
        withExposedPorts(HTTP_PORT);
        withCopyFileToContainer(MountableFile.forClasspathResource(configResource), "/goff/goff-proxy.yaml");
        withCopyFileToContainer(MountableFile.forClasspathResource("flags.goff.yaml"), "/goff/flags.goff.yaml");
        withCommand("/go-feature-flag", "--config", "/goff/goff-proxy.yaml");
        waitingFor(Wait.forHttp("/health").forPort(HTTP_PORT).forStatusCode(200));
    }

    public String getConnectionInfo() {
        return "http://" + getHost() + ":" + getMappedPort(HTTP_PORT);
    }
}
