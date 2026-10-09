package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

public class FlagdUnixSocketWithoutNativeTransportTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.flagd.url", "unix:///var/run/flagd.sock")
            .assertException(t -> {
                for (Throwable cause = t; cause != null; cause = cause.getCause()) {
                    if (cause.getMessage() != null
                            && cause.getMessage().contains("quarkus.vertx.prefer-native-transport")) {
                        return;
                    }
                }
                fail("Expected a failure naming quarkus.vertx.prefer-native-transport, got", t);
            });

    @Test
    void trigger() {
    }
}
