package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.FlagdTestContainer;
import io.quarkus.test.QuarkusUnitTest;

@EnabledOnOs(OS.LINUX)
public class FlagdUnixSocketTest {
    private static final Path SOCKET_DIR = createSocketDir();

    private static final FlagdTestContainer flagd = new FlagdTestContainer.Builder("flags.json")
            .withUnixSocketDir(SOCKET_DIR)
            .build();

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("flags.json"))
            .overrideConfigKey("quarkus.vertx.prefer-native-transport", "true");

    static {
        test.setBeforeAllCustomizer(() -> {
            flagd.start();
            test.overrideConfigKey("quarkus.openfeature.flagd.url", "unix://" + flagd.getUnixSocketPath());
        });
        test.setAfterAllCustomizer(flagd::stop);
    }

    @Inject
    Client client;

    @Test
    void evaluatesOverADomainSocket() {
        assertThat(client.getProviderState()).isEqualTo(ProviderState.READY);
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello from flagd");
        assertThat(client.getStringValue("missing-flag", "fallback")).isEqualTo("fallback");
    }

    private static Path createSocketDir() {
        try {
            Path dir = Files.createTempDirectory("flagd-socket");
            dir.toFile().deleteOnExit();
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
