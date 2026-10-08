package io.quarkiverse.openfeature.unleash.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.Value;
import io.getunleash.engine.Context;

public class UnleashContextMappingTest {
    private static Context mapContext(Map<String, Value> attributes) {
        return UnleashFeatureProvider.mapContext(new ImmutableContext(attributes), null, null);
    }

    @Test
    public void stringPropertyIsPassedThrough() {
        Context context = mapContext(Map.of("plan", new Value("premium")));

        assertEquals("premium", context.getProperties().get("plan"));
    }

    @Test
    public void numericPropertiesAreStringified() {
        Context context = mapContext(Map.of(
                "age", new Value(42),
                "count", new Value(9_000_000_000L),
                "score", new Value(4.5)));

        assertEquals("42", context.getProperties().get("age"));
        assertEquals("9000000000", context.getProperties().get("count"));
        assertEquals("4.5", context.getProperties().get("score"));
    }

    @Test
    public void booleanPropertyIsStringified() {
        Context context = mapContext(Map.of("beta", new Value(true)));

        assertEquals("true", context.getProperties().get("beta"));
    }

    @Test
    public void instantPropertyBecomesIso8601() {
        Context context = mapContext(Map.of("signedUpAt", new Value(Instant.parse("2026-10-08T12:34:56Z"))));

        assertEquals("2026-10-08T12:34:56Z", context.getProperties().get("signedUpAt"));
    }

    @Test
    public void listAndStructurePropertiesAreSkipped() {
        Context context = mapContext(Map.of(
                "tags", new Value(List.of(new Value("a"), new Value("b"))),
                "address", new Value(new ImmutableStructure(Map.of("city", new Value("Brno"))))));

        assertTrue(context.getProperties() == null || context.getProperties().isEmpty());
    }

    @Test
    public void knownAttributesAcceptNonStringValues() {
        Context context = mapContext(Map.of(
                "sessionId", new Value(12345),
                "currentTime", new Value(Instant.parse("2026-10-08T12:34:56Z"))));

        assertEquals("12345", context.getSessionId());
        assertEquals("2026-10-08T12:34:56Z", context.getCurrentTime());
        assertFalse(context.getProperties() != null && context.getProperties().containsKey("sessionId"));
    }
}
