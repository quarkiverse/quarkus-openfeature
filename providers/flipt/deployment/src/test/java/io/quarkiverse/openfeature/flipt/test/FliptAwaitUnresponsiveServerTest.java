package io.quarkiverse.openfeature.flipt.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.StallingHttpServer;
import io.quarkus.test.QuarkusUnitTest;

/**
 * A server that accepts the stream and then stays silent must not hold up startup for as long
 * as the stream deadline, which defaults to 10 minutes. Awaiting is bounded, and once the wait
 * expires the application starts with the provider in ERROR and flags falling back to defaults.
 */
public class FliptAwaitUnresponsiveServerTest {
    private static StallingHttpServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "true")
            .setLogRecordPredicate(record -> record.getMessage() != null && record.getMessage().contains("giving up"))
            .assertLogRecords(records -> {
                // Flipt answers the auth check and the stream headers, so nothing else bounds
                // the wait and the initialization timeout is what ends it
                assertThat(records).hasSize(1);
            });

    static {
        test.setBeforeAllCustomizer(() -> {
            // accept the stream and then stay silent; the stream deadline is left at its default
            server = StallingHttpServer.start(request -> {
                request.response().setChunked(true).write("");
            });
            test.overrideConfigKey("quarkus.openfeature.flipt.url", "http://localhost:" + server.port());
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
