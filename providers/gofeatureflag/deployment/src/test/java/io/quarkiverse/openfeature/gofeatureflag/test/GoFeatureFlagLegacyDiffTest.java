package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkiverse.openfeature.test.runtime.StallingHttpServer;
import io.quarkus.test.QuarkusUnitTest;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;

/**
 * The relay proxy used to send flag diffs before v1.56.0. This test verifies
 * diffs are still applied correctly.
 */
public class GoFeatureFlagLegacyDiffTest {
    private static final String SSE_PATH = "/stream/v1/sse/flag/change";

    private static final String CONFIGURATION = """
            {"flags":{\
            "string-flag":{"variations":{"greeting":"hello"},"defaultRule":{"variation":"greeting"}},\
            "removed-flag":{"variations":{"greeting":"gone"},"defaultRule":{"variation":"greeting"}}\
            }}""";

    private static final String DIFF = """
            {\
            "updated":{"string-flag":{\
            "old_value":{"variations":{"greeting":"hello"},"defaultRule":{"variation":"greeting"}},\
            "new_value":{"variations":{"greeting":"updated"},"defaultRule":{"variation":"greeting"}}}},\
            "added":{"added-flag":{"variations":{"greeting":"new"},"defaultRule":{"variation":"greeting"}}},\
            "deleted":{"removed-flag":{"variations":{"greeting":"gone"},"defaultRule":{"variation":"greeting"}}}\
            }""";

    private static StallingHttpServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "true");

    static {
        test.setBeforeAllCustomizer(() -> {
            server = StallingHttpServer.start(request -> {
                if (SSE_PATH.equals(request.path())) {
                    HttpServerResponse response = request.response().setChunked(true);
                    response.write("");
                    Vertx.currentContext().owner().setTimer(2000, ignored -> {
                        response.write("data: " + DIFF + "\n");
                    });
                } else {
                    request.response().end(CONFIGURATION);
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
    void diffIsStillApplied() {
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello");
        assertThat(client.getStringValue("removed-flag", "default")).isEqualTo("gone");
        assertThat(client.getStringValue("added-flag", "default")).isEqualTo("default");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(client.getStringValue("string-flag", "default")).isEqualTo("updated");
        });
        assertThat(client.getStringValue("added-flag", "default")).isEqualTo("new");
        assertThat(client.getStringValue("removed-flag", "default")).isEqualTo("default");
    }
}
