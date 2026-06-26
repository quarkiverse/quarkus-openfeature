package io.quarkiverse.openfeature.test.runtime;

import java.util.Objects;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

public class FliptTestContainer extends GenericContainer<FliptTestContainer> {
    public static final int HTTP_PORT = 8080;

    private final boolean tls;

    private FliptTestContainer(String configResource, String keyPath, String certPath) {
        super("flipt/flipt:v2");
        withExposedPorts(HTTP_PORT);

        if (configResource != null) {
            withCopyFileToContainer(MountableFile.forClasspathResource(configResource), "/etc/flipt/config/default.yml");
        }

        tls = keyPath != null && certPath != null;
        if (tls) {
            withCopyFileToContainer(MountableFile.forHostPath(keyPath), "/certs/server.key");
            withCopyFileToContainer(MountableFile.forHostPath(certPath), "/certs/server.crt");
            waitingFor(Wait.forHttps("/").forPort(HTTP_PORT).allowInsecure().forStatusCode(200));
        } else {
            waitingFor(Wait.forHttp("/").forPort(HTTP_PORT).forStatusCode(200));
        }
    }

    public String getConnectionInfo() {
        return (tls ? "https" : "http") + "://" + getHost() + ":" + getMappedPort(HTTP_PORT);
    }

    public static class Builder {
        private final String configResource;
        private String keyPath;
        private String certPath;

        /**
         * @param configResource classpath resource mounted as {@code /etc/flipt/config/default.yml},
         *        or {@code null} to use the default Flipt configuration (no auth, no TLS)
         */
        public Builder(String configResource) {
            this.configResource = configResource;
        }

        public Builder withTls(String keyPath, String certPath) {
            this.keyPath = Objects.requireNonNull(keyPath, "keyPath");
            this.certPath = Objects.requireNonNull(certPath, "certPath");
            return this;
        }

        public FliptTestContainer build() {
            return new FliptTestContainer(configResource, keyPath, certPath);
        }
    }
}
