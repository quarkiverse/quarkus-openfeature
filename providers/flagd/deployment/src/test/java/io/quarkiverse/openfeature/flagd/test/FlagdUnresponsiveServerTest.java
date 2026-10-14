package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.StallingGrpcServer;
import io.quarkus.test.QuarkusUnitTest;

/**
 * A sync stream that is accepted but never responds anything must not keep the provider
 * waiting forever; the stream deadline has to expire and a reconnect has to follow.
 */
public class FlagdUnresponsiveServerTest {
    private static StallingGrpcServer server;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.await-providers", "false")
            .overrideConfigKey("quarkus.openfeature.flagd.stream-deadline", "1s");

    static {
        test.setBeforeAllCustomizer(() -> {
            server = StallingGrpcServer.start();
            test.overrideConfigKey("quarkus.openfeature.flagd.url", "localhost:" + server.port());
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
