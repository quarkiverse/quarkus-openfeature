package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkiverse.openfeature.test.runtime.StallingHttpServer;
import io.quarkus.test.QuarkusUnitTest;
import io.vertx.core.http.HttpServerResponse;

/**
 * When the configuration fetch fails after the SSE stream is already connected, exactly
 * one reconnect must follow, and the stream it establishes must then be left alone.
 */
public class GoFeatureFlagFailedConfigFetchTest {
    private static final String SSE_PATH = "/stream/v1/sse/flag/change";

    private static final String COUNT_PATH = "/test/sse-connections";

    private static final String CONFIGURATION = "{\"flags\":{\"string-flag\":{\"variations\":{\"greeting\":\"hello\"},"
            + "\"defaultRule\":{\"variation\":\"greeting\"}}}}";

    private static StallingHttpServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest();

    static {
        test.setBeforeAllCustomizer(() -> {
            AtomicInteger sseConnections = new AtomicInteger();
            AtomicInteger fetches = new AtomicInteger();
            server = StallingHttpServer.start(request -> {
                if (SSE_PATH.equals(request.path())) {
                    sseConnections.incrementAndGet();
                    HttpServerResponse response = request.response().setChunked(true);
                    response.write("");
                } else if (COUNT_PATH.equals(request.path())) {
                    request.response().end(String.valueOf(sseConnections.get()));
                } else {
                    if (fetches.incrementAndGet() == 1) {
                        request.response().setStatusCode(500).end();
                    } else {
                        request.response().end(CONFIGURATION);
                    }
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

    @ConfigProperty(name = "quarkus.openfeature.gofeatureflag.url")
    String url;

    @Test
    void oneFailedConfigFetchCausesOneReconnect() throws Exception {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello");
        });

        // the reconnect backoff would have fired several more times by now
        Thread.sleep(8_000);
        assertThat(sseConnections()).isEqualTo(2);
    }

    private int sseConnections() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url + COUNT_PATH)).build(),
                HttpResponse.BodyHandlers.ofString());
        return Integer.parseInt(response.body());
    }
}
