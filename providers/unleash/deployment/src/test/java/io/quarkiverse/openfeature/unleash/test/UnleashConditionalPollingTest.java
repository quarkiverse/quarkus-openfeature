package io.quarkiverse.openfeature.unleash.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EventDetails;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Conditional polling against a real Unleash server, which answers {@code 304} to a request
 * carrying the ETag it last issued.
 * <p>
 * What a real server lets us observe is the consequence rather than the header: every poll that
 * comes back {@code 200} is a configuration change as far as the provider is concerned, so before
 * polls were conditional, this interval produced a {@code PROVIDER_CONFIGURATION_CHANGED} roughly
 * every 200 ms while nothing at all was changing. {@link UnleashEtagTest} covers the headers
 * themselves, which only a server under the test's control can show.
 */
public class UnleashConditionalPollingTest {
    private static final long POLL_INTERVAL_MILLIS = 200;
    private static final int POLLS_TO_OBSERVE = 10;

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("unleash.json"))
            .overrideConfigKey("quarkus.openfeature.unleash.poll-interval", POLL_INTERVAL_MILLIS + "ms");

    @Inject
    Client client;

    @Test
    void unchangedFlagsEmitNoConfigurationChange() throws InterruptedException {
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();

        List<EventDetails> changes = new CopyOnWriteArrayList<>();
        client.onProviderConfigurationChanged(changes::add);

        Thread.sleep(POLL_INTERVAL_MILLIS * POLLS_TO_OBSERVE);

        assertThat(changes).isEmpty();
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
    }
}
