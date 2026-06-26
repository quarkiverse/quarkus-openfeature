package io.quarkiverse.openfeature.flipt.test;

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
import io.quarkiverse.openfeature.flipt.deployment.FliptImporter;
import io.quarkiverse.openfeature.test.runtime.Disconnect;
import io.quarkiverse.openfeature.test.runtime.FliptTestContainer;
import io.quarkiverse.openfeature.test.runtime.Reconnect;
import io.quarkiverse.openfeature.test.runtime.TcpProxyExtension;
import io.quarkus.test.QuarkusUnitTest;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class FliptSlowReconnectionTest {
    private static final FliptTestContainer flipt = new FliptTestContainer.Builder(null).build();

    @RegisterExtension
    @Order(1)
    static final TcpProxyExtension proxy = TcpProxyExtension.create(flipt, FliptTestContainer.HTTP_PORT, container -> {
        String url = "http://" + container.getHost() + ":" + container.getMappedPort(FliptTestContainer.HTTP_PORT);
        FliptImporter.run(url, Thread.currentThread().getContextClassLoader().getResourceAsStream("features.yaml"));
    });

    @RegisterExtension
    @Order(2)
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("features.yaml"));

    static {
        test.setBeforeAllCustomizer(() -> {
            test.overrideConfigKey("quarkus.openfeature.flipt.url", "http://localhost:" + proxy.port());
            test.overrideConfigKey("quarkus.openfeature.flipt.grace-period", "3s");
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
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("greeting");
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
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("greeting");
    }

    @Test
    @Order(5)
    void cachedDataWhileError() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(client.getProviderState()).isEqualTo(ProviderState.ERROR);
        });
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("greeting");
    }

    @Test
    @Order(6)
    @Reconnect
    void reconnect() {
    }

    @Test
    @Order(7)
    void evaluateAfterReconnect() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(client.getProviderState()).isEqualTo(ProviderState.READY);
        });
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("greeting");
    }
}
