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
import io.quarkus.test.QuarkusExtensionTest;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;

/**
 * The relay proxy stopped sending flag diffs in v1.56.0; it now only signals that
 * the configuration changed and expects the client to re-fetch it.
 */
public class GoFeatureFlagRefetchEventTest {
    private static final String SSE_PATH = "/stream/v1/sse/flag/change";

    private static String configuration(String greeting) {
        return """
                {"flags":{"string-flag":{"variations":{"greeting":"%s"},"defaultRule":{"variation":"greeting"}}}}\
                """.formatted(greeting);
    }

    private static StallingHttpServer server;

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "true");

    static {
        test.setBeforeAllCustomizer(() -> {
            AtomicInteger fetches = new AtomicInteger();
            server = StallingHttpServer.start(request -> {
                if (SSE_PATH.equals(request.path())) {
                    HttpServerResponse response = request.response().setChunked(true);
                    response.write(": connected\n\n");
                    Vertx.currentContext().owner().setTimer(2000, ignored -> {
                        response.write("""
                                id: 1793868026745825379
                                data: {"type":"refetchEvaluation","lastModified":1793868026}
                                event: message

                                """);
                    });
                } else {
                    request.response().end(configuration(fetches.incrementAndGet() == 1 ? "hello" : "refetched"));
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
    void sseEventMakesTheClientRefetch() {
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(client.getStringValue("string-flag", "default")).isEqualTo("refetched");
        });
    }
}
