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
 * A configuration request that is accepted and never answered must not leave the provider
 * waiting forever; the request timeout has to expire and a reconnect has to follow.
 */
public class GoFeatureFlagUnresponsiveServerTest {
    private static final String SSE_PATH = "/stream/v1/sse/flag/change";

    private static StallingHttpServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "false");

    static {
        test.setBeforeAllCustomizer(() -> {
            // the configuration request stays unanswered
            server = StallingHttpServer.start(request -> {
                if (SSE_PATH.equals(request.path())) {
                    request.response().setChunked(true).write("");
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
    void providerInErrorState() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(client.getProviderState()).isEqualTo(ProviderState.ERROR);
        });
        assertThat(client.getBooleanValue("any-flag", true)).isTrue();
    }
}
