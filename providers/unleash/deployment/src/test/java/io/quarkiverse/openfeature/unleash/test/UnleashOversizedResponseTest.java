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
 * A response above the configured maximum must not be buffered whole; it has to be reported
 * with a message that says which setting to raise.
 */
public class UnleashOversizedResponseTest {
    private static final int PADDING_SIZE = 128 * 1024;

    private static StallingHttpServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "true")
            .overrideConfigKey("quarkus.openfeature.unleash.max-configuration-size", "64K")
            .setLogRecordPredicate(record -> record.getMessage() != null
                    && record.getMessage().contains("Failed to read Unleash response body"))
            .assertLogRecords(records -> {
                assertThat(records).isNotEmpty();
                assertThat(records.get(0).getThrown()).hasMessageContaining(
                        "flag configuration exceeds the configured maximum of 65536 bytes")
                        .hasMessageContaining("quarkus.openfeature.unleash.max-configuration-size");
            });

    static {
        test.setBeforeAllCustomizer(() -> {
            server = StallingHttpServer.start(request -> {
                request.response()
                        .putHeader("Content-Type", "application/json")
                        .end("{\"version\":2,\"features\":[],\"padding\":\"" + "x".repeat(PADDING_SIZE) + "\"}");
            });
            test.overrideConfigKey("quarkus.openfeature.unleash.url", "http://localhost:" + server.port());
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
