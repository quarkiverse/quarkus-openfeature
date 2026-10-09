package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

public class FlagdOfflineMissingFileTest {
    private static final String MISSING_PATH = "/this/path/does/not/exist/flags.json";

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.flagd.offline-path", MISSING_PATH)
            .assertException(t -> {
                // offline mode reads the file on the calling thread, so a missing file fails
                // application startup rather than ending up in ProviderState.ERROR
                for (Throwable cause = t; cause != null; cause = cause.getCause()) {
                    if (cause.getMessage() != null && cause.getMessage().contains(MISSING_PATH)) {
                        return;
                    }
                }
                fail("Expected a failure naming " + MISSING_PATH + ", got", t);
            });

    @Test
    void trigger() {
    }
}
