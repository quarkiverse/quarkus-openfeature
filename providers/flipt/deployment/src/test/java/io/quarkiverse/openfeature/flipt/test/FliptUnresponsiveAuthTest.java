package io.quarkiverse.openfeature.flipt.test;

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
 * An authentication check that is accepted and never answered must not block the provider
 * from reaching the flag stream. The stream here answers with an error, which is how the
 * test tells "we got there" from "we are still waiting for the auth check".
 */
public class FliptUnresponsiveAuthTest {
    private static final String STREAM_PATH = "/client/v2/environments/default/namespaces/default/stream";

    private static StallingHttpServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "false")
            .overrideConfigKey("quarkus.openfeature.flipt.auth-type", "client_token")
            .overrideConfigKey("quarkus.openfeature.flipt.auth-token", "secret");

    static {
        test.setBeforeAllCustomizer(() -> {
            // the authentication check stays unanswered
            server = StallingHttpServer.start(request -> {
                if (STREAM_PATH.equals(request.path())) {
                    request.response().setStatusCode(503).end();
                }
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
    void providerInErrorState() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(client.getProviderState()).isEqualTo(ProviderState.ERROR);
        });
        assertThat(client.getBooleanValue("any-flag", true)).isTrue();
    }
}
