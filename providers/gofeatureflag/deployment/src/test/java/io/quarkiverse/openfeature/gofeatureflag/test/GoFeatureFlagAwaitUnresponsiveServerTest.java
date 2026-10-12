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
 * Awaiting a server that accepts requests and then stays silent must not hold up startup.
 * Here the SSE stream is accepted but the full configuration fetch that follows it never gets
 * an answer, so the provider's own request timeout is what gives up first; the initialization
 * timeout in {@code SyncClientState} sits above it and never has to intervene. Either way the
 * application starts, with the provider in ERROR and flags falling back to defaults.
 */
public class GoFeatureFlagAwaitUnresponsiveServerTest {
    private static final String SSE_PATH = "/stream/v1/sse/flag/change";

    private static StallingHttpServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "true")
            .setLogRecordPredicate(record -> record.getMessage() != null && record.getMessage().contains("giving up"))
            .assertLogRecords(records -> {
                // the provider's own request timeout is lower, so it reports the failure first
                // and the initialization timeout never gets to preempt it
                assertThat(records).isEmpty();
            });

    static {
        test.setBeforeAllCustomizer(() -> {
            // the stream deadline and the request timeout are both left at their defaults
            server = StallingHttpServer.start(request -> {
                if (SSE_PATH.equals(request.path())) {
                    // accept the stream and then stay silent
                    request.response().setChunked(true).write("");
                }
                // and never answer the configuration fetch at all
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
    void applicationStartsWithProviderInError() {
        assertThat(client.getProviderState()).isEqualTo(ProviderState.ERROR);
        assertThat(client.getBooleanValue("any-flag", true)).isTrue();
    }
}
