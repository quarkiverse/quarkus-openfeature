package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.StallingHttpServer;
import io.quarkus.test.QuarkusUnitTest;

/**
 * An SSE stream that is accepted but never carries any data must not keep the provider
 * reporting READY forever; the stream deadline has to expire and a reconnect has to follow.
 */
public class GoFeatureFlagUnresponsiveStreamTest {
    private static final String SSE_PATH = "/stream/v1/sse/flag/change";

    private static StallingHttpServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.gofeatureflag.stream-deadline", "1s")
            .overrideConfigKey("quarkus.openfeature.gofeatureflag.grace-period", "1s");

    static {
        test.setBeforeAllCustomizer(() -> {
            server = StallingHttpServer.start(request -> {
                if (SSE_PATH.equals(request.path())) {
                    // accept the stream and then stay silent
                    request.response().setChunked(true).write("");
                } else {
                    request.response().end("{\"flags\":{}}");
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
    void providerLeavesReadyState() {
        assertThat(client.getProviderState()).isEqualTo(ProviderState.READY);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(client.getProviderState()).isNotEqualTo(ProviderState.READY);
        });
        assertThat(client.getBooleanValue("any-flag", true)).isTrue();
    }
}
