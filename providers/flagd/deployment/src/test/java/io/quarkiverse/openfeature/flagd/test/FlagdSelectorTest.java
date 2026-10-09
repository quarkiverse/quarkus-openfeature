package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkiverse.openfeature.test.runtime.FlagdTestContainer;
import io.quarkus.test.QuarkusUnitTest;

public class FlagdSelectorTest {
    private static final FlagdTestContainer flagd = new FlagdTestContainer.Builder(
            "flags-with-selector.json", "flags-with-other-selector.json").build();

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.flagd.selector", "flagSetId=my-set");

    static {
        test.setBeforeAllCustomizer(() -> {
            flagd.start();
            test.overrideConfigKey("quarkus.openfeature.flagd.url", flagd.getConnectionInfo());
        });
        test.setAfterAllCustomizer(flagd::stop);
    }

    @Inject
    Client client;

    @Test
    void flagFromTheSelectedSet() {
        assertThat(client.getStringValue("selector-flag", "default")).isEqualTo("hello from selector");
    }

    @Test
    void flagFromTheOtherSetDoesNotExist() {
        assertThat(client.getStringValue("other-set-flag", "fallback")).isEqualTo("fallback");
    }
}
