package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkiverse.openfeature.test.runtime.StallingHttpServer;
import io.quarkus.test.QuarkusUnitTest;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;

public class GoFeatureFlagMalformedDiffTest {
    private static final String SSE_PATH = "/stream/v1/sse/flag/change";

    private static String configuration(String greeting) {
        return "{\"flags\":{\"string-flag\":{\"variations\":{\"greeting\":\"" + greeting + "\"},"
                + "\"defaultRule\":{\"variation\":\"greeting\"}}}}";
    }

    private static StallingHttpServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "true");

    static {
        test.setBeforeAllCustomizer(() -> {
            AtomicInteger fetches = new AtomicInteger();
            server = StallingHttpServer.start(request -> {
                if (SSE_PATH.equals(request.path())) {
                    HttpServerResponse response = request.response().setChunked(true);
                    response.write("");
                    // only produce a malformed diff after the 1st sync
                    if (fetches.get() > 0) {
                        return;
                    }
                    Vertx.currentContext().owner().setTimer(2000, ignored -> {
                        response.write("data: this is not JSON\n");
                    });
                } else {
                    request.response().end(configuration(fetches.incrementAndGet() == 1 ? "hello" : "resynced"));
                }
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
    void malformedDiffMakesTheClientResync() {
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(client.getStringValue("string-flag", "default")).isEqualTo("resynced");
        });
    }
}
