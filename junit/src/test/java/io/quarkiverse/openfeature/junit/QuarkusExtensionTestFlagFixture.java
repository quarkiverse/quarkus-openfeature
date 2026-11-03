package io.quarkiverse.openfeature.junit;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FlagValueType;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * Not a test on its own; {@link TestFlagUnsupportedModeTest} runs it. The class name must not
 * match a Surefire inclusion pattern, or it would also run directly and always fail.
 * <p>
 * {@code @TestFlag} cannot work under {@link QuarkusExtensionTest}: the JUnit engine instantiates
 * {@link OpenFeatureTestExtension} outside the Quarkus classloader, so it reads a different,
 * empty copy of the provider map. The override used to be dropped silently and this method
 * saw the real flag value; it must fail loudly instead.
 */
public class QuarkusExtensionTestFlagFixture {
    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .overrideConfigKey("quarkus.openfeature.runtime.string-flag", "real");

    @Inject
    Client client;

    @Test
    @TestFlag(key = "string-flag", value = "overridden", type = FlagValueType.STRING)
    void overrideIsNotSilentlyIgnored() {
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("overridden");
    }

    @Test
    void unannotatedTestStillRuns() {
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("real");
    }
}
