package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/**
 * Like a missing file, flag data that does not parse is final in offline mode: no later
 * sync can replace it, so the startup fails instead of leaving every flag on its default.
 */
public class FlagdOfflineMalformedFileTest {
    private static final Path FLAGS_FILE = writeFlagsToTempFile();

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "false")
            .overrideConfigKey("quarkus.openfeature.flagd.offline-path", FLAGS_FILE.toString())
            .assertException(e -> {
                assertThat(e).hasMessageContaining("Cannot parse the flagd offline file")
                        .hasMessageContaining(FLAGS_FILE.toString());
            });

    @Test
    void trigger() {
    }

    private static Path writeFlagsToTempFile() {
        try {
            Path file = Files.createTempFile("flagd-offline-malformed", ".json");
            file.toFile().deleteOnExit();
            Files.writeString(file, "{ this is not flag data");
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
