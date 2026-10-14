package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/**
 * Offline mode reads the file once and never connects to anything, so a file that is not
 * there is a misconfiguration and not a connection failure: nothing would ever read it
 * again, and the provider would stay in {@code ERROR} with every flag on its default for
 * the life of the process. It therefore fails the startup, awaiting providers or not.
 */
public class FlagdOfflineMissingFileTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "false")
            .overrideConfigKey("quarkus.openfeature.flagd.offline-path", "/this/path/does/not/exist/flags.json")
            .assertException(e -> {
                assertThat(e).hasMessageContaining("Cannot read the flagd offline file")
                        .hasMessageContaining("/this/path/does/not/exist/flags.json");
            });

    @Test
    void trigger() {
    }
}
