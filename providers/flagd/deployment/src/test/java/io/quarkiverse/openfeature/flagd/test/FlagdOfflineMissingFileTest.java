package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ProviderState;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Offline mode reads the file when the provider initializes. A missing file is an initialization
 * failure like any other: it does not fail application startup, it leaves the provider in ERROR
 * with every flag falling back to its default.
 */
public class FlagdOfflineMissingFileTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.flagd.offline-path", "/this/path/does/not/exist/flags.json");

    @Inject
    Client client;

    @Test
    void providerInErrorState() {
        assertThat(client.getProviderState()).isEqualTo(ProviderState.ERROR);
        assertThat(client.getBooleanValue("any-flag", true)).isTrue();
    }
}
