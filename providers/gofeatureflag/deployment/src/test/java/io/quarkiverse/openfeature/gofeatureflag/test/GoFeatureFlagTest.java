package io.quarkiverse.openfeature.gofeatureflag.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Value;
import io.quarkus.test.QuarkusUnitTest;

public class GoFeatureFlagTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("flags.goff.yaml"));

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
    void doubleFlag() {
        assertThat(client.getDoubleValue("double-flag", 0.0)).isEqualTo(3.14);
    }

    @Test
    void objectFlag() {
        Value value = client.getObjectValue("object-flag", new Value());
        assertThat(value.asStructure().getValue("theme").asString()).isEqualTo("dark");
        assertThat(value.asStructure().getValue("maxItems").asInteger()).isEqualTo(25);
    }

    // a value that is not of the requested type is a type mismatch, not an empty string:
    // `asText()` on a Jackson container node returns "", which would silently replace the
    // caller's default with a value the flag never had
    @Test
    void objectFlagEvaluatedAsStringIsATypeMismatch() {
        FlagEvaluationDetails<String> details = client.getStringDetails("object-flag", "fallback");
        assertThat(details.getValue()).isEqualTo("fallback");
        assertThat(details.getErrorCode()).isEqualTo(ErrorCode.TYPE_MISMATCH);
    }

    @Test
    void stringFlagEvaluatedAsIntegerIsATypeMismatch() {
        FlagEvaluationDetails<Integer> details = client.getIntegerDetails("string-flag", 42);
        assertThat(details.getValue()).isEqualTo(42);
        assertThat(details.getErrorCode()).isEqualTo(ErrorCode.TYPE_MISMATCH);
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
