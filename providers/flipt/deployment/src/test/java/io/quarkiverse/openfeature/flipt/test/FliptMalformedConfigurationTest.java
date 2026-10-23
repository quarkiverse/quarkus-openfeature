package io.quarkiverse.openfeature.flipt.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.StallingHttpServer;
import io.quarkus.test.QuarkusUnitTest;

public class FliptMalformedConfigurationTest {
    private static StallingHttpServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "true")
            .setLogRecordPredicate(record -> record.getMessage() != null && record.getMessage().contains("giving up"))
            .assertLogRecords(records -> {
                // the initialization timeout must not be what ends the wait
                assertThat(records).isEmpty();
            });

    static {
        test.setBeforeAllCustomizer(() -> {
            server = StallingHttpServer.start(request -> {
                request.response().setChunked(true).write("this is not JSON\n");
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
