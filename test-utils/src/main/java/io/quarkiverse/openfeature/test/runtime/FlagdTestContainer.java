package io.quarkiverse.openfeature.test.runtime;

import java.util.Objects;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

public class FlagdTestContainer extends GenericContainer<FlagdTestContainer> {
    public static final int SYNC_PORT = 8015;

    private FlagdTestContainer(String flagsResource, String keyPath, String certPath) {
        super("ghcr.io/open-feature/flagd:latest");
        withExposedPorts(SYNC_PORT);
        withCopyFileToContainer(MountableFile.forClasspathResource(flagsResource), "/flags.json");
        waitingFor(Wait.forLogMessage(".*starting flag sync service.*", 1));

        if (keyPath != null && certPath != null) {
            withCopyFileToContainer(MountableFile.forHostPath(keyPath), "/certs/server.key");
            withCopyFileToContainer(MountableFile.forHostPath(certPath), "/certs/server.crt");
            withCommand("start", "-f", "file:/flags.json",
                    "-k", "/certs/server.key", "-c", "/certs/server.crt");
        } else {
            withCommand("start", "-f", "file:/flags.json");
        }
    }

    public String getConnectionInfo() {
        return getHost() + ":" + getMappedPort(SYNC_PORT);
    }

    public static class Builder {
        private final String flagsResource;
        private String keyPath;
        private String certPath;

        /**
         * @param flagsResource classpath resource mounted as {@code /flags.json}, must not be {@code null}
         */
        public Builder(String flagsResource) {
            this.flagsResource = Objects.requireNonNull(flagsResource);
        }

        public Builder withTls(String keyPath, String certPath) {
            this.keyPath = Objects.requireNonNull(keyPath, "keyPath");
            this.certPath = Objects.requireNonNull(certPath, "certPath");
            return this;
        }

        public FlagdTestContainer build() {
            return new FlagdTestContainer(flagsResource, keyPath, certPath);
        }
    }
}
