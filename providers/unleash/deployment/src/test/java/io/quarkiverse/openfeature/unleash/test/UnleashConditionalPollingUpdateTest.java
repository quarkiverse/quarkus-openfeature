package io.quarkiverse.openfeature.unleash.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EventDetails;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Conditional polling must not make the client blind to changes: when the flags really do change,
 * the ETag the server issues changes with them, the next poll comes back {@code 200} and the new
 * value has to be applied.
 * <p>
 * Together with {@link UnleashConditionalPollingTest} this also rules out the one way that test
 * could pass for the wrong reason: a client that stopped polling altogether would emit no
 * configuration change either, but it would never see this update.
 * <p>
 * The flag is switched through the Unleash admin API, which Dev Services leaves open because it
 * starts the server with {@code AUTH_TYPE=none}. That mutates the server, so this test asks for a
 * Dev Services container of its own through a distinct service name rather than sharing the one
 * the rest of the module asserts against.
 */
public class UnleashConditionalPollingUpdateTest {
    private static final long POLL_INTERVAL_MILLIS = 200;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("unleash.json"))
            .overrideConfigKey("quarkus.openfeature.unleash.poll-interval", POLL_INTERVAL_MILLIS + "ms")
            .overrideConfigKey("quarkus.openfeature.unleash.devservices.service-name", "unleash-conditional-polling");

    @ConfigProperty(name = "quarkus.openfeature.unleash.url")
    String unleashUrl;

    @Inject
    Client client;

    @Test
    void changeIsPickedUp() throws Exception {
        assertThat(client.getBooleanValue("disabled-flag", true)).isFalse();

        List<EventDetails> changes = new CopyOnWriteArrayList<>();
        client.onProviderConfigurationChanged(changes::add);

        enableFlag("disabled-flag");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(client.getBooleanValue("disabled-flag", false)).isTrue();
        });
        assertThat(changes).isNotEmpty();
    }

    private void enableFlag(String flag) throws Exception {
        URI uri = URI.create(unleashUrl
                + "/admin/projects/default/features/" + flag + "/environments/development/on");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .describedAs("Unleash admin API response: %s", response.body())
                .isBetween(200, 299);
    }
}
