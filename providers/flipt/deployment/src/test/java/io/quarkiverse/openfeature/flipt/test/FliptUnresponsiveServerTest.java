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
 * A stream that is accepted but never carries any data must not keep the provider waiting
 * forever; the stream deadline has to expire and a reconnect has to follow.
 */
public class FliptUnresponsiveServerTest {
    private static StallingHttpServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "false")
            .overrideConfigKey("quarkus.openfeature.flipt.stream-deadline", "1s");

    static {
        test.setBeforeAllCustomizer(() -> {
            // accept the stream and then stay silent
            server = StallingHttpServer.start(request -> {
                request.response().setChunked(true).write("");
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
