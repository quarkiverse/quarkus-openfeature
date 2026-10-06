package io.quarkiverse.openfeature.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.FlagNotFoundError;
import dev.openfeature.sdk.exceptions.TypeMismatchError;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class ConfigFeatureProvider implements FeatureProvider, DevFeatureAccess, OverrideFeatureAccess {
    private final String name;
    private final Map<String, Value> flags;
    private volatile FlagOverrides flagOverrides;

    public ConfigFeatureProvider(String name, Map<String, String> flags) {
        this.name = name;

        Map<String, Value> parsed = new HashMap<>(flags.size());
        flags.forEach((key, value) -> parsed.put(key, parse(value)));
        this.flags = Map.copyOf(parsed);
    }

    private static Value parse(String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return new Value(Boolean.parseBoolean(value));
        }

        try {
            return new Value(Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
        }

        try {
            return new Value(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
        }

        try {
            return new Value(Double.parseDouble(value));
        } catch (NumberFormatException ignored) {
        }

        // Only an object and an array are read as JSON. A JSON scalar is either already
        // handled above, or it might be a quoted string, in which case parsing it as JSON
        // would strip the quotes off, changing the configured value.
        try {
            Object json = Json.decodeValue(value);
            if (json instanceof JsonObject || json instanceof JsonArray) {
                return jsonValue(json);
            }
        } catch (DecodeException ignored) {
        }

        return new Value(value);
    }

    @Override
    public Metadata getMetadata() {
        return () -> name;
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Boolean> override = evaluateFlagOverride(key, Boolean.class);
        if (override != null) {
            return override;
        }
        Value value = resolveFlag(key);
        // a value that is not a boolean is false, it is never an error
        return ProviderEvaluation.<Boolean> builder()
                .value(value.isBoolean() ? value.asBoolean() : false)
                .reason(Reason.STATIC.toString())
                .build();
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<String> override = evaluateFlagOverride(key, String.class);
        if (override != null) {
            return override;
        }
        return ProviderEvaluation.<String> builder()
                .value(asText(resolveFlag(key)))
                .reason(Reason.STATIC.toString())
                .build();
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Integer> override = evaluateFlagOverride(key, Integer.class);
        if (override != null) {
            return override;
        }
        Value value = resolveFlag(key);
        if (!(value.asObject() instanceof Integer number)) {
            throw new TypeMismatchError("Cannot parse as integer: " + asText(value));
        }
        return ProviderEvaluation.<Integer> builder()
                .value(number)
                .reason(Reason.STATIC.toString())
                .build();
    }

    @Override
    public ProviderEvaluation<Long> getLongEvaluation(String key, Long defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Long> override = evaluateFlagOverride(key, Long.class);
        if (override != null) {
            return override;
        }
        Value value = resolveFlag(key);
        // a value that fits an int is written as an int, but it is still a valid long
        Object number = value.asObject();
        if (!(number instanceof Integer) && !(number instanceof Long)) {
            throw new TypeMismatchError("Cannot parse as long: " + asText(value));
        }
        return ProviderEvaluation.<Long> builder()
                .value(((Number) number).longValue())
                .reason(Reason.STATIC.toString())
                .build();
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Double> override = evaluateFlagOverride(key, Double.class);
        if (override != null) {
            return override;
        }
        Value value = resolveFlag(key);
        // every number converts to a double, whether it was written as one or not
        if (!(value.asObject() instanceof Number number)) {
            throw new TypeMismatchError("Cannot parse as double: " + asText(value));
        }
        return ProviderEvaluation.<Double> builder()
                .value(number.doubleValue())
                .reason(Reason.STATIC.toString())
                .build();
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Value> override = evaluateFlagOverride(key, Value.class);
        if (override != null) {
            return override;
        }
        return ProviderEvaluation.<Value> builder()
                .value(resolveFlag(key))
                .reason(Reason.STATIC.toString())
                .build();
    }

    private Value resolveFlag(String key) {
        Value value = flags.get(key);
        if (value == null) {
            throw new FlagNotFoundError("Flag not found: " + key);
        }
        return value;
    }

    private static String asText(Value value) {
        if (value.isString()) {
            return value.asString();
        } else if (value.isStructure() || value.isList()) {
            return Json.encode(jsonObject(value));
        }
        return String.valueOf(value.asObject());
    }

    private static Object jsonObject(Value value) {
        if (value.isStructure()) {
            JsonObject object = new JsonObject();
            for (Map.Entry<String, Value> entry : value.asStructure().asUnmodifiableMap().entrySet()) {
                object.put(entry.getKey(), jsonObject(entry.getValue()));
            }
            return object;
        } else if (value.isList()) {
            JsonArray array = new JsonArray();
            for (Value item : value.asList()) {
                array.add(jsonObject(item));
            }
            return array;
        }
        return value.asObject();
    }

    private static Value jsonValue(Object json) {
        if (json == null) {
            return new Value();
        } else if (json instanceof JsonObject object) {
            Map<String, Value> map = new HashMap<>();
            for (Map.Entry<String, Object> entry : object) {
                map.put(entry.getKey(), jsonValue(entry.getValue()));
            }
            return new Value(new ImmutableStructure(map));
        } else if (json instanceof JsonArray array) {
            List<Value> list = new ArrayList<>(array.size());
            for (Object item : array) {
                list.add(jsonValue(item));
            }
            return new Value(list);
        } else if (json instanceof Boolean bool) {
            return new Value(bool);
        } else if (json instanceof Integer integer) {
            return new Value(integer);
        } else if (json instanceof Long number) {
            return new Value(number);
        } else if (json instanceof Number number) {
            return new Value(number.doubleValue());
        }
        return new Value(json.toString());
    }

    @Override
    public Collection<FlagInfo> getFlags() {
        List<FlagInfo> result = new ArrayList<>(flags.size());
        for (Map.Entry<String, Value> entry : flags.entrySet()) {
            result.add(new FlagInfo(entry.getKey(), typeOf(entry.getValue())));
        }
        return result;
    }

    private static FlagValueType typeOf(Value value) {
        Object object = value.asObject();
        if (object instanceof Boolean) {
            return FlagValueType.BOOLEAN;
        } else if (object instanceof Integer) {
            return FlagValueType.INTEGER;
        } else if (object instanceof Long) {
            return FlagValueType.LONG;
        } else if (object instanceof Number) {
            return FlagValueType.DOUBLE;
        } else if (value.isStructure() || value.isList()) {
            return FlagValueType.OBJECT;
        }
        return FlagValueType.STRING;
    }

    @Override
    public void setFlagOverrides(FlagOverrides overrides) {
        this.flagOverrides = overrides;
    }

    @Override
    public void clearFlagOverrides() {
        this.flagOverrides = null;
    }

    protected final <T> ProviderEvaluation<T> evaluateFlagOverride(String key, Class<T> expectedType) {
        FlagOverrides flagOverrides = this.flagOverrides;
        return flagOverrides != null ? flagOverrides.evaluate(key, expectedType) : null;
    }
}
