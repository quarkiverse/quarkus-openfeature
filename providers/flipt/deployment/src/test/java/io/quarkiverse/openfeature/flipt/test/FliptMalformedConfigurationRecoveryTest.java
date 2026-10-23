package io.quarkiverse.openfeature.flipt.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.StallingHttpServer;
import io.quarkus.test.QuarkusUnitTest;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;

public class FliptMalformedConfigurationRecoveryTest {
    private static final String SNAPSHOT = "{\"result\":{\"namespace\":{\"key\":\"default\"},\"flags\":["
            + "{\"key\":\"recovered-flag\",\"name\":\"recovered-flag\",\"type\":\"BOOLEAN_FLAG_TYPE\","
            + "\"enabled\":true,\"rules\":[],\"rollouts\":[]}]}}\n";

    private static StallingHttpServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "false");

    static {
        test.setBeforeAllCustomizer(() -> {
            AtomicInteger connections = new AtomicInteger();
            server = StallingHttpServer.start(request -> {
                HttpServerResponse response = request.response().setChunked(true);
                if (connections.incrementAndGet() > 1) {
                    return;
                }
                response.write("this is not JSON\n");
                Vertx.currentContext().owner().setTimer(2000, id -> {
                    response.write(SNAPSHOT);
                });
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
    void laterSnapshotOnTheSameStreamRecovers() {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(client.getProviderState()).isEqualTo(ProviderState.READY);
        });
        assertThat(client.getBooleanValue("recovered-flag", false)).isTrue();
    }
}
