package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.openfeature.test.runtime.StallingHttpServer;
import io.quarkus.test.QuarkusUnitTest;

/**
 * A server whose SSE stream always fails but whose configuration fetch always succeeds must
 * still back off. The fetch succeeding says nothing about the stream, so it must not restart
 * the reconnect delay of a connection whose stream has failed.
 */
public class GoFeatureFlagBrokenStreamBackoffTest {
    private static final String SSE_PATH = "/stream/v1/sse/flag/change";

    private static final String TIMES_PATH = "/test/sse-connection-times";

    /** Larger than `max-configuration-size`, so the SSE record cap trips on every connection. */
    private static final int PADDING_SIZE = 128 * 1024;

    /** Enough connections to compare a late reconnect delay against the first one. */
    private static final int WANTED_CONNECTIONS = 4;

    private static StallingHttpServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.gofeatureflag.max-configuration-size", "64K");

    static {
        test.setBeforeAllCustomizer(() -> {
            List<Long> connectionTimes = Collections.synchronizedList(new ArrayList<>());
            server = StallingHttpServer.start(request -> {
                if (SSE_PATH.equals(request.path())) {
                    connectionTimes.add(System.currentTimeMillis());
                    // one SSE line that goes on past the maximum and never ends
                    request.response().setChunked(true).write("data: " + "x".repeat(PADDING_SIZE));
                } else if (TIMES_PATH.equals(request.path())) {
                    request.response().end(connectionTimes.stream()
                            .map(String::valueOf)
                            .collect(Collectors.joining(",")));
                } else {
                    // the configuration fetch always succeeds, so only the stream is broken
                    request.response().end("{\"flags\":{}}");
                }
            });
            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.url", "http://localhost:" + server.port());
        });
        test.setAfterAllCustomizer(() -> {
            server.stop();
        });
    }

    @ConfigProperty(name = "quarkus.openfeature.gofeatureflag.url")
    String url;

    @Test
    void aBrokenStreamBacksOffEvenWhenTheConfigurationFetchSucceeds() throws Exception {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(connectionTimes()).hasSizeGreaterThanOrEqualTo(WANTED_CONNECTIONS);
        });

        List<Long> times = connectionTimes();
        List<Long> delays = new ArrayList<>();
        for (int i = 1; i < times.size(); i++) {
            delays.add(times.get(i) - times.get(i - 1));
        }

        // the delay should roughly double in each iteration, so here, we assert that
        // the last is at least 2x the first (it should be 4x in theory)
        assertThat(delays.get(delays.size() - 1))
                .as("reconnect delays should grow exponentially: %s", delays)
                .isGreaterThanOrEqualTo(2 * delays.get(0));
    }

    /**
     * Read over HTTP rather than from a static field: the customizer that starts the server runs
     * outside the Quarkus classloader and this test method runs inside it, so each would see its
     * own copy of the field.
     */
    private List<Long> connectionTimes() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url + TIMES_PATH)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.body().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(response.body().split(","))
                .map(Long::valueOf)
                .toList();
    }
}
