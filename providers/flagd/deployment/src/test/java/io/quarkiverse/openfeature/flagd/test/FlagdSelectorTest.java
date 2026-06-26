package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkus.test.QuarkusUnitTest;

public class FlagdSelectorTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("flags-with-selector.json", "flags.json"))
            .overrideConfigKey("quarkus.openfeature.flagd.selector", "flagSetId=my-set");

    @Inject
    Client client;

    @Test
    void selectorFlag() {
        assertThat(client.getStringValue("selector-flag", "default")).isEqualTo("hello from selector");
    }
}
