package io.quarkiverse.openfeature.runtime;

import java.util.Map;

import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.exceptions.TypeMismatchError;

/**
 * Contains a map of overridden flag values and evaluation logic for them. Used by providers that
 * implement {@link TestFeatureAccess}.
 */
public final class TestOverrides {
    private final Map<String, Object> overrides;

    public TestOverrides(Map<String, Object> overrides) {
        this.overrides = overrides;
    }

    @SuppressWarnings("unchecked")
    public <T> ProviderEvaluation<T> evaluate(String key, Class<T> expectedType) {
        if (overrides == null) {
            return null;
        }
        Object value = overrides.get(key);
        if (value == null) {
            return null;
        }
        if (!expectedType.isInstance(value)) {
            throw new TypeMismatchError("Flag \"" + key + "\" is not of expected type: " + expectedType);
        }
        return ProviderEvaluation.<T> builder()
                .value((T) value)
                .reason(Reason.STATIC.toString())
                .build();
    }
}
