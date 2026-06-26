package io.quarkiverse.openfeature.junit;

import java.lang.reflect.AnnotatedElement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.FlagValueType;
import io.quarkiverse.openfeature.runtime.OpenFeatureBuildTimeConfig;
import io.quarkiverse.openfeature.runtime.OpenFeatureRecorder;
import io.quarkiverse.openfeature.runtime.TestFeatureAccess;
import io.quarkiverse.openfeature.runtime.TestOverrides;

class OpenFeatureTestExtension implements BeforeEachCallback, AfterEachCallback {
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(
            OpenFeatureTestExtension.class);

    private static final String OVERRIDDEN_DOMAINS_KEY = "overriddenDomains";

    @Override
    public void beforeEach(ExtensionContext context) {
        Map<String, Map<String, Object>> overridesByDomain = collectOverrides(context);
        if (overridesByDomain.isEmpty()) {
            return;
        }

        Set<String> overriddenDomains = new HashSet<>();
        for (Map.Entry<String, Map<String, Object>> entry : overridesByDomain.entrySet()) {
            String domain = entry.getKey();
            TestOverrides overrides = new TestOverrides(entry.getValue());
            for (FeatureProvider provider : OpenFeatureRecorder.getProviders(domain)) {
                if (provider instanceof TestFeatureAccess testAccess) {
                    testAccess.setTestOverrides(overrides);
                    overriddenDomains.add(domain);
                } else {
                    throw new IllegalStateException("Provider \"" + provider.getMetadata().getName()
                            + "\" does not support test overrides");
                }
            }
        }

        context.getStore(NAMESPACE).put(OVERRIDDEN_DOMAINS_KEY, overriddenDomains);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void afterEach(ExtensionContext context) {
        Set<String> overriddenDomains = (Set<String>) context.getStore(NAMESPACE)
                .remove(OVERRIDDEN_DOMAINS_KEY);
        if (overriddenDomains == null) {
            return;
        }
        for (String domain : overriddenDomains) {
            for (FeatureProvider provider : OpenFeatureRecorder.getProviders(domain)) {
                if (provider instanceof TestFeatureAccess testAccess) {
                    testAccess.clearTestOverrides();
                }
            }
        }
    }

    private Map<String, Map<String, Object>> collectOverrides(ExtensionContext context) {
        Map<String, Map<String, Object>> result = new HashMap<>();

        context.getTestClass().ifPresent(clazz -> collectFromElement(clazz, result));
        context.getTestMethod().ifPresent(method -> collectFromElement(method, result));

        return result;
    }

    private void collectFromElement(AnnotatedElement element, Map<String, Map<String, Object>> result) {
        TestFlag[] annotations = element.getAnnotationsByType(TestFlag.class);
        for (TestFlag annotation : annotations) {
            String domain = annotation.domain().isEmpty()
                    ? OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN
                    : annotation.domain();
            result.computeIfAbsent(domain, k -> new HashMap<>())
                    .put(annotation.key(), convertValue(annotation.value(), annotation.type()));
        }
    }

    private Object convertValue(String value, FlagValueType type) {
        return switch (type) {
            case BOOLEAN -> Boolean.parseBoolean(value);
            case INTEGER -> Integer.parseInt(value);
            case DOUBLE -> Double.parseDouble(value);
            case STRING -> value;
            case OBJECT -> throw new IllegalArgumentException("OBJECT type not supported by @" + TestFlag.class.getName());
        };
    }
}
