package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.StallingGrpcServer;
import io.quarkus.test.QuarkusUnitTest;

/**
 * A server that accepts the sync call and then stays silent must not hold up startup for as long
 * as the stream deadline, which defaults to 10 minutes. Awaiting is bounded, and once the wait
 * expires the application starts with the provider in ERROR and flags falling back to defaults.
 */
public class FlagdAwaitUnresponsiveServerTest {
    private static StallingGrpcServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "true")
            .setLogRecordPredicate(record -> record.getMessage() != null && record.getMessage().contains("giving up"))
            .assertLogRecords(records -> {
                // flagd answers the response headers, so nothing else bounds the wait and the
                // initialization timeout is what ends it
                assertThat(records).hasSize(1);
            });

    static {
        test.setBeforeAllCustomizer(() -> {
            // accept the call and then stay silent; the stream deadline is left at its default
            server = StallingGrpcServer.start();
            test.overrideConfigKey("quarkus.openfeature.flagd.url", "localhost:" + server.port());
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
