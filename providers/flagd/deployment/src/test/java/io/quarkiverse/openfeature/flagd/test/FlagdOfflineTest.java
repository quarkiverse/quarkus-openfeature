package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkus.test.QuarkusUnitTest;

public class FlagdOfflineTest {
    // written out instead of loaded from `src/test/resources`: the flags must live in a plain
    // file outside the application archive, because offline mode reads the file system
    private static final String FLAGS = """
            {
              "flags": {
                "bool-flag": {
                  "state": "ENABLED",
                  "variants": { "on": true, "off": false },
                  "defaultVariant": "on"
                },
                "string-flag": {
                  "state": "ENABLED",
                  "variants": { "greeting": "hello from a file" },
                  "defaultVariant": "greeting"
                }
              }
            }
            """;

    private static final Path FLAGS_FILE = writeFlagsToTempFile();

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.flagd.offline-path", FLAGS_FILE.toString());

    @Inject
    Client client;

    @Test
    void readsFlagsFromFile() {
        assertThat(client.getProviderState()).isEqualTo(ProviderState.READY);
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello from a file");
        assertThat(client.getStringValue("missing-flag", "fallback")).isEqualTo("fallback");
    }

    @Test
    void devServicesNotStarted() {
        // Dev Services would overwrite the flagd URL with the container address, which always
        // has a randomly mapped port; offline mode must not pay for a container it never talks to
        assertThat(ConfigProvider.getConfig().getValue("quarkus.openfeature.flagd.url", String.class))
                .isEqualTo("localhost:8015");
    }

    private static Path writeFlagsToTempFile() {
        try {
            Path file = Files.createTempFile("flagd-offline", ".json");
            file.toFile().deleteOnExit();
            Files.writeString(file, FLAGS);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
