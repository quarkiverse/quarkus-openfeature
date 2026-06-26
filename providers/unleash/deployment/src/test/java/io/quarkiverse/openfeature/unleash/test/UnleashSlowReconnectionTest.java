package io.quarkiverse.openfeature.unleash.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.test.runtime.Disconnect;
import io.quarkiverse.openfeature.test.runtime.Reconnect;
import io.quarkiverse.openfeature.test.runtime.TcpProxyExtension;
import io.quarkiverse.openfeature.test.runtime.UnleashTestContainer;
import io.quarkus.test.QuarkusUnitTest;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class UnleashSlowReconnectionTest {
    private static final UnleashTestContainer unleash = new UnleashTestContainer("unleash.json");

    @RegisterExtension
    @Order(1)
    static final TcpProxyExtension proxy = TcpProxyExtension.create(unleash, UnleashTestContainer.HTTP_PORT);

    @RegisterExtension
    @Order(2)
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("unleash.json"));

    static {
        test.setBeforeAllCustomizer(() -> {
            test.overrideConfigKey("quarkus.openfeature.unleash.url", "http://localhost:" + proxy.port() + "/api");
            test.overrideConfigKey("quarkus.openfeature.unleash.api-key", UnleashTestContainer.API_TOKEN);
            test.overrideConfigKey("quarkus.openfeature.unleash.poll-interval", "2s");
            test.overrideConfigKey("quarkus.openfeature.unleash.grace-period", "3s");
        });
    }

    @Inject
    Client client;

    @Test
    @Order(1)
    void providerIsReady() {
        assertThat(client.getProviderState()).isEqualTo(ProviderState.READY);
    }

    @Test
    @Order(2)
    void evaluateBeforeDisconnect() {
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello");
    }

    @Test
    @Order(3)
    @Disconnect
    void disconnect() {
    }

    @Test
    @Order(4)
    void cachedDataWhileStale() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(client.getProviderState()).isEqualTo(ProviderState.STALE);
        });
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello");
    }

    @Test
    @Order(5)
    void waitForError() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(client.getProviderState()).isEqualTo(ProviderState.ERROR);
        });
    }

    @Test
    @Order(6)
    void cachedDataWhileError() {
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello");
    }

    @Test
    @Order(7)
    @Reconnect
    void reconnect() {
    }

    @Test
    @Order(8)
    void evaluateAfterReconnect() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(client.getProviderState()).isEqualTo(ProviderState.READY);
        });
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello");
    }
}
