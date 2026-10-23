package io.quarkiverse.openfeature.flagd.test;

import static io.quarkiverse.openfeature.flagd.test.SyncFlagsResponses.flagConfiguration;
import static io.quarkiverse.openfeature.flagd.test.SyncFlagsResponses.grpcFrame;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.StallingGrpcServer;
import io.quarkus.test.QuarkusUnitTest;

/**
 * A flag configuration larger than the gRPC client's default maximum message size of 256 KB
 * must still be received; a deployment with a few hundred flags easily exceeds that.
 */
public class FlagdLargeFlagConfigurationTest {
    private static final int PADDING_SIZE = 512 * 1024;

    private static StallingGrpcServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "true");

    static {
        test.setBeforeAllCustomizer(() -> {
            server = StallingGrpcServer.start(request -> {
                // answer the sync call with one large message and keep the stream open,
                // which is what flagd does with a large flag configuration
                request.response()
                        .putHeader("content-type", "application/grpc")
                        .write(grpcFrame(flagConfiguration(PADDING_SIZE)));
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
    void largeFlagConfigurationIsReceived() {
        assertThat(client.getProviderState()).isEqualTo(ProviderState.READY);
        assertThat(client.getStringValue("big-flag", "default"))
                .isEqualTo("hello from a large flag configuration");
    }
}
