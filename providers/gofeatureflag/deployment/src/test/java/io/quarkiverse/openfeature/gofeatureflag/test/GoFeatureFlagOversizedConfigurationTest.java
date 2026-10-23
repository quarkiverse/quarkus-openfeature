package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.StallingHttpServer;
import io.quarkus.test.QuarkusUnitTest;

/**
 * The full flag configuration arrives as one HTTP response, so a server that answers with more
 * than the configured maximum must not be buffered whole; it has to be reported with a message
 * that says which setting to raise.
 */
public class GoFeatureFlagOversizedConfigurationTest {
    private static final String SSE_PATH = "/stream/v1/sse/flag/change";

    private static final int PADDING_SIZE = 128 * 1024;

    private static StallingHttpServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "true")
            .overrideConfigKey("quarkus.openfeature.gofeatureflag.max-configuration-size", "64K")
            .setLogRecordPredicate(record -> record.getMessage() != null
                    && record.getMessage().contains("Failed to read response body"))
            .assertLogRecords(records -> {
                assertThat(records).isNotEmpty();
                assertThat(records.get(0).getThrown())
                        .hasMessageContaining("flag configuration exceeds the configured maximum of 65536 bytes")
                        .hasMessageContaining("quarkus.openfeature.gofeatureflag.max-configuration-size");
            });

    static {
        test.setBeforeAllCustomizer(() -> {
            server = StallingHttpServer.start(request -> {
                if (SSE_PATH.equals(request.path())) {
                    // accept the stream and then stay silent
                    request.response().setChunked(true).write("");
                } else {
                    request.response()
                            .end("{\"flags\":{},\"padding\":\"" + "x".repeat(PADDING_SIZE) + "\"}");
                }
            });
            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.url", "http://localhost:" + server.port());
        });
        test.setAfterAllCustomizer(() -> {
            server.stop();
        });
    }

    @Inject
    Client client;

    @Test
    void providerStaysInError() {
        assertThat(client.getProviderState()).isEqualTo(ProviderState.ERROR);
        assertThat(client.getBooleanValue("any-flag", true)).isTrue();
    }
}
