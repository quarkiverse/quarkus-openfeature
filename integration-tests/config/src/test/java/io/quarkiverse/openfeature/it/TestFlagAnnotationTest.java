package io.quarkiverse.openfeature.it;

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
import io.smallrye.common.annotation.Identifier;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestFlag(key = "class-flag", value = "class-value", type = FlagValueType.STRING)
public class TestFlagAnnotationTest {
    @Inject
    Client defaultClient;

    @Inject
    @Identifier("custom")
    Client customClient;

    @Test
    @Order(1)
    @TestFlag(key = "runtime-flag", value = "overridden", type = FlagValueType.STRING)
    void overrideExistingFlag() {
        assertThat(defaultClient.getStringValue("runtime-flag", "default")).isEqualTo("overridden");
    }

    @Test
    @Order(2)
    void nonOverriddenFlagReturnsRealValue() {
        assertThat(defaultClient.getStringValue("runtime-flag", "default")).isEqualTo("from-runtime");
    }

    @Test
    @Order(3)
    void classLevelFlagIsAvailable() {
        assertThat(defaultClient.getStringValue("class-flag", "default")).isEqualTo("class-value");
    }

    @Test
    @Order(4)
    @TestFlag(key = "class-flag", value = "method-wins", type = FlagValueType.STRING)
    void methodLevelOverridesClassLevel() {
        assertThat(defaultClient.getStringValue("class-flag", "default")).isEqualTo("method-wins");
    }

    @Test
    @Order(5)
    @TestFlag(key = "bool-flag", value = "true")
    void booleanFlag() {
        assertThat(defaultClient.getBooleanValue("bool-flag", false)).isTrue();
    }

    @Test
    @Order(6)
    @TestFlag(key = "int-flag", value = "42", type = FlagValueType.INTEGER)
    void integerFlag() {
        assertThat(defaultClient.getIntegerValue("int-flag", 0)).isEqualTo(42);
    }

    @Test
    @Order(7)
    @TestFlag(key = "double-flag", value = "3.14", type = FlagValueType.DOUBLE)
    void doubleFlag() {
        assertThat(defaultClient.getDoubleValue("double-flag", 0.0)).isEqualTo(3.14);
    }

    @Test
    @Order(8)
    @TestFlag(key = "custom-flag", value = "test-override", type = FlagValueType.STRING, domain = "custom")
    void customDomainOverride() {
        assertThat(customClient.getStringValue("custom-flag", "default")).isEqualTo("test-override");
    }

    @Test
    @Order(9)
    void customDomainNotOverriddenAfterPreviousTest() {
        assertThat(customClient.getStringValue("custom-flag", "default")).isEqualTo("custom-value");
    }

    @Test
    @Order(10)
    @TestFlag(key = "runtime-flag", value = "overridden-again", type = FlagValueType.STRING)
    @TestFlag(key = "new-flag", value = "brand-new", type = FlagValueType.STRING)
    void multipleMethodFlags() {
        assertThat(defaultClient.getStringValue("runtime-flag", "default")).isEqualTo("overridden-again");
        assertThat(defaultClient.getStringValue("new-flag", "default")).isEqualTo("brand-new");
    }

    @Test
    @Order(11)
    @TestFlag(key = "runtime-flag", value = "overridden", type = FlagValueType.STRING)
    void overriddenFlagCoexistsWithRealFlags() {
        assertThat(defaultClient.getStringValue("runtime-flag", "default")).isEqualTo("overridden");
        assertThat(defaultClient.getStringValue("buildtime-flag", "default")).isEqualTo("from-buildtime");
    }
}
