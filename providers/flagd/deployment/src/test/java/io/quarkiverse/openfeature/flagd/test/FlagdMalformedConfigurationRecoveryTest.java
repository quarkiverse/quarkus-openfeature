package io.quarkiverse.openfeature.flagd.test;

import static io.quarkiverse.openfeature.flagd.test.SyncFlagsResponses.flagConfiguration;
import static io.quarkiverse.openfeature.flagd.test.SyncFlagsResponses.grpcFrame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.StallingGrpcServer;
import io.quarkus.test.QuarkusUnitTest;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;

public class FlagdMalformedConfigurationRecoveryTest {
    private static StallingGrpcServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "false");

    static {
        test.setBeforeAllCustomizer(() -> {
            AtomicInteger calls = new AtomicInteger();
            server = StallingGrpcServer.start(request -> {
                HttpServerResponse response = request.response()
                        .putHeader("content-type", "application/grpc");
                if (calls.incrementAndGet() > 1) {
                    response.write(Buffer.buffer());
                    return;
                }
                response.write(grpcFrame("this is not JSON"));
                Vertx.currentContext().owner().setTimer(2000, id -> {
                    response.write(grpcFrame(flagConfiguration(0)));
                });
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
    void laterConfigurationOnTheSameStreamRecovers() {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(client.getProviderState()).isEqualTo(ProviderState.READY);
        });
        assertThat(client.getStringValue("big-flag", "default"))
                .isEqualTo("hello from a large flag configuration");
    }
}
