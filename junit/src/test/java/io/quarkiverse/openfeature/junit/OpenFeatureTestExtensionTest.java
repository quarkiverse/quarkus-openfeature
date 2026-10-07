package io.quarkiverse.openfeature.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import io.quarkiverse.openfeature.runtime.FlagOverrides;
import io.quarkiverse.openfeature.runtime.OpenFeatureBuildTimeConfig;
import io.quarkiverse.openfeature.runtime.OverrideFeatureAccess;

public class OpenFeatureTestExtensionTest {
    private static final String DEFAULT_DOMAIN = OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN;

    private static class TestProvider implements FeatureProvider {
        private final String name;

        TestProvider(String name) {
            this.name = name;
        }

        @Override
        public Metadata getMetadata() {
            return () -> name;
        }

        @Override
        public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
            throw new UnsupportedOperationException();
        }
    }

    private static class OverridableProvider extends TestProvider implements OverrideFeatureAccess {
        FlagOverrides overrides;

        OverridableProvider(String name) {
            super(name);
        }

        @Override
        public void setFlagOverrides(FlagOverrides overrides) {
            this.overrides = overrides;
        }

        @Override
        public void clearFlagOverrides() {
            this.overrides = null;
        }
    }

    private static OpenFeatureTestExtension extensionWith(Map<String, List<FeatureProvider>> providers) {
        OpenFeatureTestExtension extension = new OpenFeatureTestExtension();
        extension.providerLookup = domain -> providers.getOrDefault(domain, List.of());
        return extension;
    }

    @Test
    public void overridesAreAppliedToAllProvidersInAllDomains() {
        OverridableProvider one = new OverridableProvider("one");
        OverridableProvider two = new OverridableProvider("two");
        OverridableProvider three = new OverridableProvider("three");
        OpenFeatureTestExtension extension = extensionWith(Map.of(
                DEFAULT_DOMAIN, List.of(one, two),
                "other", List.of(three)));

        Set<String> overridden = extension.applyOverrides(Map.of(
                DEFAULT_DOMAIN, Map.of("flag", true),
                "other", Map.of("flag", "value")));

        assertEquals(Set.of(DEFAULT_DOMAIN, "other"), overridden);
        assertTrue(one.overrides.evaluate("flag", Boolean.class).getValue());
        assertTrue(two.overrides.evaluate("flag", Boolean.class).getValue());
        assertEquals("value", three.overrides.evaluate("flag", String.class).getValue());

        extension.clearOverrides(overridden);

        assertNull(one.overrides);
        assertNull(two.overrides);
        assertNull(three.overrides);
    }

    @Test
    public void domainWithoutProvidersIsNotReportedAsOverridden() {
        OpenFeatureTestExtension extension = extensionWith(Map.of());

        assertEquals(Set.of(), extension.applyOverrides(Map.of(DEFAULT_DOMAIN, Map.of("flag", true))));
    }

    @Test
    public void unsupportedProviderLeavesNoDomainOverridden() {
        OverridableProvider overridable = new OverridableProvider("overridable");
        OpenFeatureTestExtension extension = extensionWith(Map.of(
                DEFAULT_DOMAIN, List.of(overridable),
                "other", List.of(new TestProvider("plain"))));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> extension.applyOverrides(Map.of(
                DEFAULT_DOMAIN, Map.of("flag", true),
                "other", Map.of("flag", true))));

        assertEquals("Provider \"plain\" does not support test overrides", e.getMessage());
        assertNull(overridable.overrides);
    }

    @Test
    public void unsupportedProviderAmongSupportedOnesInOneDomain() {
        OverridableProvider overridable = new OverridableProvider("overridable");
        OpenFeatureTestExtension extension = extensionWith(Map.of(
                DEFAULT_DOMAIN, List.of(overridable, new TestProvider("plain"))));

        assertThrows(IllegalStateException.class,
                () -> extension.applyOverrides(Map.of(DEFAULT_DOMAIN, Map.of("flag", true))));

        assertNull(overridable.overrides);
    }

    @TestFlag(key = "class-flag", value = "true")
    @TestFlag(key = "overridden", value = "false")
    static class AnnotatedFixture {
        @TestFlag(key = "overridden", value = "true")
        @TestFlag(domain = "other", key = "string-flag", value = "hello", type = FlagValueType.STRING)
        @TestFlag(key = "int-flag", value = "42", type = FlagValueType.INTEGER)
        @TestFlag(key = "long-flag", value = "42", type = FlagValueType.LONG)
        @TestFlag(key = "double-flag", value = "2.5", type = FlagValueType.DOUBLE)
        void method() {
        }

        @TestFlag(key = "object-flag", value = "{}", type = FlagValueType.OBJECT)
        void objectMethod() {
        }
    }

    private static Map<String, Map<String, Object>> collect(String methodName) throws NoSuchMethodException {
        Method method = AnnotatedFixture.class.getDeclaredMethod(methodName);
        Map<String, Map<String, Object>> result = new HashMap<>();
        OpenFeatureTestExtension extension = new OpenFeatureTestExtension();
        extension.collectFromElement(AnnotatedFixture.class, result);
        extension.collectFromElement(method, result);
        return result;
    }

    @Test
    public void methodAnnotationsWinOverClassAnnotations() throws NoSuchMethodException {
        Map<String, Map<String, Object>> result = collect("method");

        assertEquals(Set.of(DEFAULT_DOMAIN, "other"), result.keySet());
        assertEquals(Map.of(
                "class-flag", true,
                "overridden", true,
                "int-flag", 42,
                "long-flag", 42L,
                "double-flag", 2.5), result.get(DEFAULT_DOMAIN));
        assertEquals(Map.of("string-flag", "hello"), result.get("other"));
    }

    @Test
    public void objectTypeIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> collect("objectMethod"));
        assertTrue(e.getMessage().contains("OBJECT type not supported"));
    }
}
