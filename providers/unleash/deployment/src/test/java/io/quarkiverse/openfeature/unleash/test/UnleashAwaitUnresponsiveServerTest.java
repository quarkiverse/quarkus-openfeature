package io.quarkiverse.openfeature.unleash.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.StallingHttpServer;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Awaiting a server that accepts the poll request and never answers it must not hold up startup.
 * Unleash bounds this itself through its request timeout, so that is what gives up first and the
 * initialization timeout in {@code SyncClientState} never has to intervene; it is deliberately
 * set above the request timeout so that the provider gets to report the failure in its own words.
 * Either way the application starts, with the provider in ERROR and flags falling back to
 * defaults.
 */
public class UnleashAwaitUnresponsiveServerTest {
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
            // the request timeout is left at its default
            server = StallingHttpServer.start();
            test.overrideConfigKey("quarkus.openfeature.unleash.url", "http://localhost:" + server.port());
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
