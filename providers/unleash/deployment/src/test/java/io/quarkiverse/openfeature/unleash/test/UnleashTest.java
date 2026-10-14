package io.quarkiverse.openfeature.unleash.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Reason;
import io.quarkus.test.QuarkusUnitTest;

public class UnleashTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("unleash.json"));

    @Inject
    Client client;

    @Test
    void booleanFlag() {
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
    }

    @Test
    void stringFlag() {
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello");
    }

    @Test
    void integerFlag() {
        assertThat(client.getIntegerValue("int-flag", 0)).isEqualTo(10);
    }

    @Test
    void longFlag() {
        assertThat(client.getLongValue("long-flag", 0L)).isEqualTo(10_000_000_000L);
    }

    @Test
    void disabledFlagReturnsDefault() {
        assertThat(client.getBooleanValue("disabled-flag", false)).isFalse();
    }

    @Test
    void missingFlagReturnsDefault() {
        assertThat(client.getStringValue("missing-flag", "fallback")).isEqualTo("fallback");
    }

    @Test
    void booleanFlagReasonIsUnknown() {
        assertThat(client.getBooleanDetails("bool-flag", false).getReason())
                .isEqualTo(Reason.UNKNOWN.name());
        assertThat(client.getBooleanDetails("disabled-flag", true).getReason())
                .isEqualTo(Reason.UNKNOWN.name());
    }

    @Test
    void resolvedVariantReasonIsUnknown() {
        FlagEvaluationDetails<String> details = client.getStringDetails("string-flag", "fallback");
        assertThat(details.getValue()).isEqualTo("hello");
        assertThat(details.getReason()).isEqualTo(Reason.UNKNOWN.name());
    }

    // boolean flag has no variants, so none resolves and the default path applies
    // the engine's placeholder is named "disabled" and that name must not escape
    @Test
    void enabledFlagWithoutVariantsReturnsDefault() {
        FlagEvaluationDetails<String> details = client.getStringDetails("bool-flag", "fallback");
        assertThat(details.getValue()).isEqualTo("fallback");
        assertThat(details.getReason()).isEqualTo(Reason.DEFAULT.name());
    }

    // the flag is disabled, so the engine returns the "disabled" placeholder
    @Test
    void disabledFlagVariantReturnsDefault() {
        FlagEvaluationDetails<String> details = client.getStringDetails("disabled-flag", "fallback");
        assertThat(details.getValue()).isEqualTo("fallback");
        assertThat(details.getReason()).isEqualTo(Reason.DEFAULT.name());
    }
}
