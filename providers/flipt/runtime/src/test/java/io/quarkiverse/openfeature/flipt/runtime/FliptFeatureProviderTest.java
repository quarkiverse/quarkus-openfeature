package io.quarkiverse.openfeature.flipt.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.openfeature.sdk.Value;

public class FliptFeatureProviderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Value parse(String json) throws Exception {
        return FliptFeatureProvider.jsonNodeToValue(MAPPER.readTree(json));
    }

    @Test
    public void intValueStaysInt() throws Exception {
        assertEquals(10, parse("10").asInteger());
    }

    @Test
    public void longValueIsNotTruncated() throws Exception {
        assertEquals(10_000_000_000L, parse("10000000000").asLong());
    }

    @Test
    public void longValueInObjectIsNotTruncated() throws Exception {
        Value value = parse("{\"count\":10000000000}");
        assertEquals(10_000_000_000L, value.asStructure().getValue("count").asLong());
    }

    @Test
    public void longValueInArrayIsNotTruncated() throws Exception {
        Value value = parse("[10000000000]");
        assertEquals(10_000_000_000L, value.asList().get(0).asLong());
    }

    @Test
    public void doubleValueStaysDouble() throws Exception {
        assertEquals(3.14, parse("3.14").asDouble());
    }
}
