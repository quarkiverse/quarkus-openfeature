package io.quarkiverse.openfeature.it.gofeatureflag;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FlagValueType;
import io.quarkiverse.openfeature.junit.TestFlag;
import io.quarkus.test.junit.QuarkusTest;

/**
 * {@code @TestFlag} against a remote-sync provider, whose override implementation lives in
 * {@code AbstractRemoteFeatureProvider} and is distinct from the one in {@code ConfigFeatureProvider}
 * that {@code integration-tests/config} covers. Each of the four remote-sync providers has its own
 * copy of this test, because the guard that consults the overrides is repeated in every
 * {@code get*Evaluation} method of every provider.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestFlag(key = "class-flag", value = "class-value", type = FlagValueType.STRING)
public class TestFlagAnnotationTest {
    @Inject
    Client client;

    @Test
    @Order(1)
    @TestFlag(key = "string-flag", value = "overridden", type = FlagValueType.STRING)
    void overrideFlagThatExistsOnTheServer() {
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("overridden");
    }

    @Test
    @Order(2)
    void nonOverriddenFlagReturnsTheServerValue() {
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello");
    }

    @Test
    @Order(3)
    @TestFlag(key = "missing-flag", value = "invented", type = FlagValueType.STRING)
    void overrideFlagThatDoesNotExistOnTheServer() {
        assertThat(client.getStringValue("missing-flag", "default")).isEqualTo("invented");
    }

    @Test
    @Order(4)
    void classLevelFlagIsAvailable() {
        assertThat(client.getStringValue("class-flag", "default")).isEqualTo("class-value");
    }

    @Test
    @Order(5)
    @TestFlag(key = "class-flag", value = "method-wins", type = FlagValueType.STRING)
    void methodLevelOverridesClassLevel() {
        assertThat(client.getStringValue("class-flag", "default")).isEqualTo("method-wins");
    }

    @Test
    @Order(6)
    @TestFlag(key = "bool-flag", value = "false")
    void booleanFlag() {
        assertThat(client.getBooleanValue("bool-flag", true)).isFalse();
    }

    @Test
    @Order(7)
    @TestFlag(key = "int-flag", value = "42", type = FlagValueType.INTEGER)
    void integerFlag() {
        assertThat(client.getIntegerValue("int-flag", 0)).isEqualTo(42);
    }

    // a provider that resolves long flags through the SDK's double path also exercises
    // the numeric coercion in FlagOverrides here
    @Test
    @Order(8)
    @TestFlag(key = "long-flag", value = "10000000000", type = FlagValueType.LONG)
    void longFlag() {
        assertThat(client.getLongValue("long-flag", 0L)).isEqualTo(10_000_000_000L);
    }

    @Test
    @Order(9)
    @TestFlag(key = "double-flag", value = "3.14", type = FlagValueType.DOUBLE)
    void doubleFlag() {
        assertThat(client.getDoubleValue("double-flag", 0.0)).isEqualTo(3.14);
    }

    @Test
    @Order(10)
    @TestFlag(key = "string-flag", value = "overridden-again", type = FlagValueType.STRING)
    @TestFlag(key = "bool-flag", value = "false")
    void multipleFlagsOfDifferentTypes() {
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("overridden-again");
        assertThat(client.getBooleanValue("bool-flag", true)).isFalse();
    }

    @Test
    @Order(11)
    @TestFlag(key = "string-flag", value = "overridden", type = FlagValueType.STRING)
    void overriddenFlagCoexistsWithServerFlags() {
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("overridden");
        assertThat(client.getIntegerValue("int-flag", 0)).isEqualTo(10);
    }

    @Test
    @Order(12)
    @TestFlag(key = "targeted-flag", value = "overridden", type = FlagValueType.STRING)
    void overrideWinsOverTargeting() {
        assertThat(client.getStringValue("targeted-flag", "default")).isEqualTo("overridden");
    }
}
