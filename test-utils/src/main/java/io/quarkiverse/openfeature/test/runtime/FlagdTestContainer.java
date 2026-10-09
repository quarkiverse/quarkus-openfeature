package io.quarkiverse.openfeature.test.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

public class FlagdTestContainer extends GenericContainer<FlagdTestContainer> {
    public static final int SYNC_PORT = 8015;

    private static final String CONTAINER_SOCKET_DIR = "/sockets";
    private static final String SOCKET_NAME = "flagd.sock";

    private Path socketPath;

    private FlagdTestContainer(List<String> flagsResources, String keyPath, String certPath, Path socketDir) {
        super("ghcr.io/open-feature/flagd:latest");
        withExposedPorts(SYNC_PORT);
        waitingFor(Wait.forLogMessage(".*starting flag sync service.*", 1));

        List<String> sources = new ArrayList<>();
        for (int i = 0; i < flagsResources.size(); i++) {
            String path = "/flags-" + i + ".json";
            withCopyFileToContainer(MountableFile.forClasspathResource(flagsResources.get(i)), path);
            sources.add("file:" + path);
        }

        if (socketDir != null) {
            // the socket is created by the container but connected to from the host, which only
            // works because a bind mount is the same inode on both sides of a local Docker daemon
            this.socketPath = socketDir.resolve(SOCKET_NAME);
            withFileSystemBind(socketDir.toAbsolutePath().toString(), CONTAINER_SOCKET_DIR, BindMode.READ_WRITE);
            // connecting to a socket needs write permission on it, so it has to be owned
            // by the host user rather than by the image's own user
            withCreateContainerCmdModifier(cmd -> cmd.withUser(owner(socketDir)));
            withCommand(command(sources, "-e", CONTAINER_SOCKET_DIR + "/" + SOCKET_NAME));
        } else if (keyPath != null && certPath != null) {
            withCopyFileToContainer(MountableFile.forHostPath(keyPath), "/certs/server.key");
            withCopyFileToContainer(MountableFile.forHostPath(certPath), "/certs/server.crt");
            withCommand(command(sources, "-k", "/certs/server.key", "-c", "/certs/server.crt"));
        } else {
            withCommand(command(sources));
        }
    }

    private static String[] command(List<String> sources, String... extraArgs) {
        List<String> command = new ArrayList<>(List.of("start"));
        for (String source : sources) {
            command.add("-f");
            command.add(source);
        }
        command.addAll(List.of(extraArgs));
        return command.toArray(new String[0]);
    }

    private static String owner(Path socketDir) {
        try {
            return String.valueOf(Files.getAttribute(socketDir, "unix:uid"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path getUnixSocketPath() {
        return socketPath;
    }

    public String getConnectionInfo() {
        return getHost() + ":" + getMappedPort(SYNC_PORT);
    }

    public static class Builder {
        private final List<String> flagsResources = new ArrayList<>();
        private String keyPath;
        private String certPath;
        private Path socketDir;

        /**
         * @param flagsResources classpath resources, each becoming one flagd source, in order;
         *        must not be {@code null} and must not be empty
         */
        public Builder(String... flagsResources) {
            if (flagsResources.length == 0) {
                throw new IllegalArgumentException("At least one flag source is required");
            }
            for (String flagsResource : flagsResources) {
                this.flagsResources.add(Objects.requireNonNull(flagsResource));
            }
        }

        public Builder withTls(String keyPath, String certPath) {
            this.keyPath = Objects.requireNonNull(keyPath, "keyPath");
            this.certPath = Objects.requireNonNull(certPath, "certPath");
            return this;
        }

        /**
         * Makes the sync service listen on a Unix domain socket in {@code socketDir} instead of
         * a TCP port. The directory must be writable by the container's user.
         * <p>
         * This only works on Unix systems, such as Linux.
         */
        public Builder withUnixSocketDir(Path socketDir) {
            this.socketDir = Objects.requireNonNull(socketDir, "socketDir");
            return this;
        }

        public FlagdTestContainer build() {
            return new FlagdTestContainer(flagsResources, keyPath, certPath, socketDir);
        }
    }
}
