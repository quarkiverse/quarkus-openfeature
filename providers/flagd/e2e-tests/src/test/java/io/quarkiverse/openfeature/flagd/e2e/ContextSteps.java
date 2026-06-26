package io.quarkiverse.openfeature.flagd.e2e;

import java.util.HashMap;
import java.util.Map;

import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.Value;
import io.cucumber.java.en.Given;

public class ContextSteps {
    private final TestState state;

    public ContextSteps(TestState state) {
        this.state = state;
    }

    @Given("a context containing a key {string}, with type {string} and with value {string}")
    public void contextWithKey(String key, String type, String value) {
        Map<String, Value> attributes = new HashMap<>();
        if (state.context != null) {
            attributes.putAll(state.context.asMap());
        }
        attributes.put(key, toValue(value, type));
        state.context = new ImmutableContext(attributes);
    }

    @Given("a context containing a targeting key with value {string}")
    public void contextWithTargetingKey(String targetingKey) {
        Map<String, Value> attributes = new HashMap<>();
        if (state.context != null) {
            attributes.putAll(state.context.asMap());
        }
        state.context = new ImmutableContext(targetingKey, attributes);
    }

    @Given("a context containing a nested property with outer key {string} and inner key {string}, with value {string}")
    public void contextWithNestedProperty(String outer, String inner, String value) {
        Map<String, Value> attributes = new HashMap<>();
        if (state.context != null) {
            attributes.putAll(state.context.asMap());
        }
        Map<String, Value> innerMap = new HashMap<>();
        innerMap.put(inner, new Value(value));
        attributes.put(outer, new Value(new ImmutableStructure(innerMap)));
        state.context = new ImmutableContext(attributes);
    }

    private static Value toValue(String value, String type) {
        return switch (type) {
            case "String" -> new Value(value);
            case "Boolean" -> new Value(Boolean.parseBoolean(value));
            case "Integer" -> {
                long longVal = Long.parseLong(value);
                if (longVal >= Integer.MIN_VALUE && longVal <= Integer.MAX_VALUE) {
                    yield new Value((int) longVal);
                }
                yield new Value(value);
            }
            case "Float" -> new Value(Double.parseDouble(value));
            default -> throw new AssertionError("Unknown value type: " + type);
        };
    }
}
