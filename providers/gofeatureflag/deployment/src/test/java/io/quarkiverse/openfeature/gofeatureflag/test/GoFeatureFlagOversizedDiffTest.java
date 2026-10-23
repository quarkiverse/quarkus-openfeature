package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.openfeature.test.runtime.StallingHttpServer;
import io.quarkus.test.QuarkusUnitTest;

/**
 * The same maximum bounds a single flag diff, which arrives as one line of the SSE stream: an
 * SSE line that never ends must make the client reconnect rather than buffer without bound.
 */
public class GoFeatureFlagOversizedDiffTest {
    private static final String SSE_PATH = "/stream/v1/sse/flag/change";

    private static final int PADDING_SIZE = 128 * 1024;

    private static StallingHttpServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "false")
            .overrideConfigKey("quarkus.openfeature.gofeatureflag.max-configuration-size", "64K")
            .setLogRecordPredicate(record -> record.getMessage() != null
                    && record.getMessage().contains("GO Feature Flag SSE stream error"))
            .assertLogRecords(records -> {
                assertThat(records).isNotEmpty();
                assertThat(records.get(0).getParameters()[0].toString())
                        .contains("flag diff exceeds the configured maximum of 65536 bytes")
                        .contains("quarkus.openfeature.gofeatureflag.max-configuration-size");
            });

    static {
        test.setBeforeAllCustomizer(() -> {
            server = StallingHttpServer.start(request -> {
                if (SSE_PATH.equals(request.path())) {
                    // one SSE line that goes on past the maximum and never ends
                    request.response().setChunked(true).write("data: " + "x".repeat(PADDING_SIZE));
                } else {
                    request.response().end("{\"flags\":{}}");
                }
            });
            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.url", "http://localhost:" + server.port());
        });
        test.setAfterAllCustomizer(() -> {
            server.stop();
        });
    }

    @Test
    void trigger() {
    }
}
