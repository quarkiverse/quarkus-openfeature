package io.quarkiverse.openfeature.flagd.e2e;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.Value;
import io.cucumber.java.en.Given;

public class ContextSteps {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TestState state;

    public ContextSteps(TestState state) {
        this.state = state;
    }

    // a regular expression, not a Cucumber expression: `Object` values are JSON,
    // so they contain quotes that `{string}` would not match
    @Given("^a context containing a key \"([^\"]*)\", with type \"([^\"]*)\" and with value \"(.*)\"$")
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
        if ("Null".equals(type) || ("null".equals(value) && !"String".equals(type))) {
            return new Value();
        }
        return switch (type) {
            case "String" -> new Value(value);
            case "Boolean" -> new Value(Boolean.parseBoolean(value));
            case "Integer" -> {
                long longVal = Long.parseLong(value);
                if (longVal >= Integer.MIN_VALUE && longVal <= Integer.MAX_VALUE) {
                    yield new Value((int) longVal);
                }
                // `Value` has no long variant; a double preserves the value for the
                // magnitudes the harness uses, and hashes the same as the `Float` row
                yield new Value((double) longVal);
            }
            case "Float" -> new Value(Double.parseDouble(value));
            case "Object" -> {
                try {
                    yield Value.objectToValue(MAPPER.readValue(value.isEmpty() ? "{}" : value, Object.class));
                } catch (JsonProcessingException e) {
                    throw new AssertionError("Malformed JSON value: " + value, e);
                }
            }
            default -> throw new AssertionError("Unknown value type: " + type);
        };
    }
}
