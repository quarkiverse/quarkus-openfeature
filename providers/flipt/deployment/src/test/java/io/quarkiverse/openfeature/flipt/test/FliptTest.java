package io.quarkiverse.openfeature.flipt.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import io.quarkus.test.QuarkusUnitTest;

public class FliptTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("features.yaml"));

    @Inject
    Client client;

    @Test
    void booleanFlag() {
        assertThat(client.getBooleanValue("bool-flag", false)).isTrue();
    }

    @Test
    void stringFlag() {
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("greeting");
    }

    @Test
    void integerFlag() {
        assertThat(client.getIntegerValue("int-flag", 0)).isEqualTo(10);
    }

    @Test
    void longFlag() {
        assertThat(client.getLongValue("long-flag", 0L)).isEqualTo(10_000_000_000L);
    }

    // if the variant has no attachment, Flipt sends the JSON `null` literal,
    // so an object evaluation has to fall back to the variant key, like other evaluations
    @Test
    void objectFlagWithoutAttachment() {
        assertThat(client.getObjectValue("string-flag", new Value("default")))
                .isEqualTo(new Value("greeting"));
    }

    // no rule matched, so there is no variant and nothing to resolve
    // (BTW this is also the only evaluation in which Flipt reports no variant attachment
    // at all, rather than the JSON `null` literal)
    @Test
    void flagWithNoMatchingVariantReturnsDefault() {
        assertThat(client.getStringValue("no-rule-flag", "fallback")).isEqualTo("fallback");
        assertThat(client.getIntegerValue("no-rule-flag", 42)).isEqualTo(42);
        assertThat(client.getLongValue("no-rule-flag", 42L)).isEqualTo(42L);
        assertThat(client.getDoubleValue("no-rule-flag", 2.72)).isEqualTo(2.72);
        assertThat(client.getObjectValue("no-rule-flag", new Value("fallback")))
                .isEqualTo(new Value("fallback"));

        FlagEvaluationDetails<String> details = client.getStringDetails("no-rule-flag", "fallback");
        assertThat(details.getReason()).isEqualTo(Reason.DEFAULT.name());
        assertThat(details.getErrorCode()).isNull();
    }

    @Test
    void disabledFlagReturnsDefault() {
        assertThat(client.getBooleanValue("disabled-flag", false)).isFalse();
    }

    @Test
    void missingFlagReturnsDefault() {
        assertThat(client.getStringValue("missing-flag", "fallback")).isEqualTo("fallback");
    }
}
