package io.quarkiverse.openfeature.unleash.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.openfeature.sdk.Value;
import io.getunleash.engine.Payload;

public class UnleashPayloadValueTest {
    private static Value valueOf(String type, String value) {
        Payload payload = new Payload();
        payload.setType(type);
        payload.setValue(value);
        return UnleashFeatureProvider.payloadValue(payload);
    }

    @Test
    public void jsonObjectPayloadIsParsed() {
        Value value = valueOf("json", "{\"color\": \"red\", \"size\": 42}");

        assertTrue(value.isStructure());
        assertEquals("red", value.asStructure().getValue("color").asString());
        assertEquals(42, value.asStructure().getValue("size").asInteger());
    }

    @Test
    public void jsonArrayPayloadIsParsed() {
        Value value = valueOf("json", "[1, \"two\", true]");

        assertTrue(value.isList());
        List<Value> list = value.asList();
        assertEquals(3, list.size());
        assertEquals(1, list.get(0).asInteger());
        assertEquals("two", list.get(1).asString());
        assertEquals(Boolean.TRUE, list.get(2).asBoolean());
    }

    @Test
    public void nestedJsonPayloadIsParsed() {
        Value value = valueOf("json", "{\"outer\": {\"inner\": [1, 2]}}");

        Value inner = value.asStructure().getValue("outer").asStructure().getValue("inner");
        assertTrue(inner.isList());
        assertEquals(2, inner.asList().size());
    }

    @Test
    public void jsonLongPayloadIsNotTruncated() {
        Value value = valueOf("json", "{\"max-bytes\": 10000000000}");

        // `asLong` would narrow a Double back to the same number, so this checks the
        // stored object: the long must survive as a Long, not as a Double
        assertEquals(Long.valueOf(10000000000L), value.asStructure().getValue("max-bytes").asObject());
    }

    @Test
    public void jsonDoublePayloadIsParsed() {
        Value value = valueOf("json", "{\"threshold\": 2.5}");

        assertEquals(2.5, value.asStructure().getValue("threshold").asDouble());
    }

    @Test
    public void jsonNullPayloadIsNullValue() {
        Value value = valueOf("json", "{\"nothing\": null}");

        assertTrue(value.asStructure().getValue("nothing").isNull());
    }

    @Test
    public void malformedJsonPayloadFallsBackToString() {
        // the payload claims to be JSON but isn't; returning the raw text is
        // more useful than failing the evaluation outright
        assertEquals("not json", valueOf("json", "not json").asString());
    }

    // A number payload resolves to the same type that `getFlags` reports for it,
    // see UnleashFlagTypeTest.numberPayloadIsNarrowestTypeThatHoldsIt.

    // `asInteger`/`asLong`/`asDouble` all narrow whatever Number is inside, so these
    // assert on `asObject` instead -- that is the only way to see the actual type.

    @Test
    public void integralNumberPayloadIsAnInteger() {
        Value value = valueOf("number", "42");

        assertTrue(value.isNumber());
        assertEquals(Integer.valueOf(42), value.asObject());
    }

    @Test
    public void largeNumberPayloadIsALong() {
        Value value = valueOf("number", "10000000000");

        assertTrue(value.isNumber());
        assertEquals(Long.valueOf(10000000000L), value.asObject());
    }

    @Test
    public void fractionalNumberPayloadIsADouble() {
        Value value = valueOf("number", "2.5");

        assertTrue(value.isNumber());
        assertEquals(Double.valueOf(2.5), value.asObject());
    }

    @Test
    public void malformedNumberPayloadFallsBackToString() {
        assertEquals("not a number", valueOf("number", "not a number").asString());
    }

    // `string` and `csv` are text; splitting a csv payload would invent semantics
    // that the server never promised.

    @Test
    public void stringPayloadStaysAString() {
        assertEquals("hello", valueOf("string", "hello").asString());
    }

    @Test
    public void csvPayloadStaysAString() {
        assertEquals("a,b,c", valueOf("csv", "a,b,c").asString());
    }

    @Test
    public void payloadWithoutTypeStaysAString() {
        Payload payload = new Payload();
        payload.setValue("{\"color\": \"red\"}");

        assertEquals("{\"color\": \"red\"}", UnleashFeatureProvider.payloadValue(payload).asString());
    }
}
