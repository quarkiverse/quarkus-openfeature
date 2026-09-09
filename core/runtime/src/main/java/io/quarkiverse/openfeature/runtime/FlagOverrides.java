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
            value = coerceNumeric(value, expectedType);
            if (value == null) {
                throw new TypeMismatchError("Flag \"" + key + "\" is not of expected type: " + expectedType);
            }
        }
        return ProviderEvaluation.<T> builder()
                .value((T) value)
                .reason(Reason.STATIC.toString())
                .build();
    }

    // A coercion is allowed only when it is lossless; anything else is a type mismatch.
    // Narrowing conversions truncate or saturate, so comparing the result back against
    // the original value rejects everything that doesn't convert exactly.
    private static Object coerceNumeric(Object value, Class<?> targetType) {
        if (value instanceof Integer i) {
            // int fits both long and double exactly
            if (targetType == Long.class) {
                return i.longValue();
            }
            if (targetType == Double.class) {
                return i.doubleValue();
            }
        } else if (value instanceof Long l) {
            if (targetType == Integer.class) {
                return l.intValue() == l ? (Object) l.intValue() : null;
            }
            // long doesn't always fit a double exactly, but a feature flag override
            // of that magnitude is not worth rejecting
            if (targetType == Double.class) {
                return l.doubleValue();
            }
        } else if (value instanceof Double d) {
            // NaN and infinity are rejected here as well: NaN compares equal to nothing
            // and infinity saturates
            if (targetType == Integer.class) {
                return d.intValue() == d ? (Object) d.intValue() : null;
            }
            if (targetType == Long.class) {
                // Negative saturation yields Long.MIN_VALUE, which is exactly representable
                // as a double and so is caught by the comparison. Positive saturation yields
                // Long.MAX_VALUE, which rounds back up to 2^63 and would therefore compare
                // equal to a d of 2^63; it is rejected outright instead. That is safe because
                // a conversion that didn't saturate always yields a long that is exactly
                // representable as a double, which Long.MAX_VALUE is not.
                long result = d.longValue();
                return result != Long.MAX_VALUE && result == d ? (Object) result : null;
            }
        }
        return null;
    }
}
