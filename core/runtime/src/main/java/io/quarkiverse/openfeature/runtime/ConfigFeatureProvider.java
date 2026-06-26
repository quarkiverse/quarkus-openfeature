package io.quarkiverse.openfeature.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.FlagNotFoundError;
import dev.openfeature.sdk.exceptions.TypeMismatchError;

public class ConfigFeatureProvider implements FeatureProvider, DevFeatureAccess {
    private final String name;
    private final Map<String, String> flags;

    public ConfigFeatureProvider(String name, Map<String, String> flags) {
        this.name = name;
        this.flags = Map.copyOf(flags);
    }

    @Override
    public Metadata getMetadata() {
        return () -> name;
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
        return ProviderEvaluation.<Boolean> builder()
                .value(Boolean.parseBoolean(resolveFlag(key)))
                .reason(Reason.STATIC.toString())
                .build();
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
        return ProviderEvaluation.<String> builder()
                .value(resolveFlag(key))
                .reason(Reason.STATIC.toString())
                .build();
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
        String value = resolveFlag(key);
        try {
            return ProviderEvaluation.<Integer> builder()
                    .value(Integer.parseInt(value))
                    .reason(Reason.STATIC.toString())
                    .build();
        } catch (NumberFormatException e) {
            throw new TypeMismatchError("Cannot parse as integer: " + value);
        }
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
        String value = resolveFlag(key);
        try {
            return ProviderEvaluation.<Double> builder()
                    .value(Double.parseDouble(value))
                    .reason(Reason.STATIC.toString())
                    .build();
        } catch (NumberFormatException e) {
            throw new TypeMismatchError("Cannot parse as double: " + value);
        }
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
        return ProviderEvaluation.<Value> builder()
                .value(new Value(resolveFlag(key)))
                .reason(Reason.STATIC.toString())
                .build();
    }

    private String resolveFlag(String key) {
        String value = flags.get(key);
        if (value == null) {
            throw new FlagNotFoundError("Flag not found: " + key);
        }
        return value;
    }

    @Override
    public Collection<FlagInfo> getFlags() {
        List<FlagInfo> result = new ArrayList<>(flags.size());
        for (Map.Entry<String, String> entry : flags.entrySet()) {
            result.add(new FlagInfo(entry.getKey(), inferType(entry.getValue())));
        }
        return result;
    }

    private static FlagValueType inferType(String value) {
        if ("true".equals(value) || "false".equals(value)) {
            return FlagValueType.BOOLEAN;
        }

        try {
            Integer.parseInt(value);
            return FlagValueType.INTEGER;
        } catch (NumberFormatException ignored) {
        }

        try {
            Double.parseDouble(value);
            return FlagValueType.DOUBLE;
        } catch (NumberFormatException ignored) {
        }

        return FlagValueType.STRING;
    }
}
