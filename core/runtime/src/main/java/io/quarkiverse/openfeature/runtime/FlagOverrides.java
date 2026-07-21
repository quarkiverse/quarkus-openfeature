package io.quarkiverse.openfeature.runtime;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.exceptions.TypeMismatchError;

/**
 * Immutable container of overridden flag values. Used by providers that
 * implement {@link OverrideFeatureAccess}.
 */
public final class FlagOverrides {
    private final Map<String, Object> overrides;

    public FlagOverrides(Map<String, Object> overrides) {
        this.overrides = overrides;
    }

    public FlagOverrides with(String key, Object value) {
        Map<String, Object> copy = new HashMap<>(overrides);
        copy.put(key, value);
        return new FlagOverrides(copy);
    }

    public FlagOverrides without(String key) {
        Map<String, Object> copy = new HashMap<>(overrides);
        copy.remove(key);
        return new FlagOverrides(copy);
    }

    public boolean isEmpty() {
        return overrides.isEmpty();
    }

    public Map<String, Object> getAll() {
        return Collections.unmodifiableMap(overrides);
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
