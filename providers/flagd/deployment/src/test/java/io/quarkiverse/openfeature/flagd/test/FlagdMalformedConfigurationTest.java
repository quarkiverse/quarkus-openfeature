package io.quarkiverse.openfeature.flagd.test;

import static io.quarkiverse.openfeature.flagd.test.SyncFlagsResponses.grpcFrame;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.StallingGrpcServer;
import io.quarkus.test.QuarkusUnitTest;

public class FlagdMalformedConfigurationTest {
    private static StallingGrpcServer server;

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
            server = StallingGrpcServer.start(request -> {
                request.response()
                        .putHeader("content-type", "application/grpc")
                        .write(grpcFrame("this is not JSON"));
            });
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
