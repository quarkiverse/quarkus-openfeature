package io.quarkiverse.openfeature.gofeatureflag.test;

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
import io.quarkiverse.openfeature.test.runtime.GoFeatureFlagTestContainer;
import io.quarkiverse.openfeature.test.runtime.Reconnect;
import io.quarkiverse.openfeature.test.runtime.TcpProxyExtension;
import io.quarkus.test.QuarkusUnitTest;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GoFeatureFlagQuickReconnectionTest {
    private static final GoFeatureFlagTestContainer goff = new GoFeatureFlagTestContainer("goff-proxy.yaml");

    @RegisterExtension
    @Order(1)
    static final TcpProxyExtension proxy = TcpProxyExtension.create(goff, GoFeatureFlagTestContainer.HTTP_PORT);

    @RegisterExtension
    @Order(2)
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("flags.goff.yaml"));

    static {
        test.setBeforeAllCustomizer(() -> {
            test.overrideConfigKey("quarkus.openfeature.gofeatureflag.url", "http://localhost:" + proxy.port());
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
    @Reconnect
    void reconnect() {
    }

    @Test
    @Order(6)
    void evaluateAfterReconnect() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(client.getProviderState()).isEqualTo(ProviderState.READY);
        });
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello");
    }
}
