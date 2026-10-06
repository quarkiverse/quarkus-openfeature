package io.quarkiverse.openfeature.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.FlagNotFoundError;
import dev.openfeature.sdk.exceptions.TypeMismatchError;

public class ConfigFeatureProviderTest {
    private static ConfigFeatureProvider provider(String configValue) {
        return new ConfigFeatureProvider("test", Map.of("flag", configValue));
    }

    private static Value object(String configValue) {
        return provider(configValue).getObjectEvaluation("flag", new Value(), null).getValue();
    }

    private static String string(String configValue) {
        return provider(configValue).getStringEvaluation("flag", null, null).getValue();
    }

    private static boolean bool(String configValue) {
        return provider(configValue).getBooleanEvaluation("flag", null, null).getValue();
    }

    private static int integer(String configValue) {
        return provider(configValue).getIntegerEvaluation("flag", null, null).getValue();
    }

    private static long longValue(String configValue) {
        return provider(configValue).getLongEvaluation("flag", null, null).getValue();
    }

    private static double doubleValue(String configValue) {
        return provider(configValue).getDoubleEvaluation("flag", null, null).getValue();
    }

    private static FlagValueType type(String configValue) {
        return provider(configValue).getFlags().iterator().next().type();
    }

    // the type of a flag is inferred from its value, once

    @Test
    void inferredType() {
        assertEquals(FlagValueType.BOOLEAN, type("true"));
        assertEquals(FlagValueType.INTEGER, type("42"));
        assertEquals(FlagValueType.LONG, type("10000000000"));
        assertEquals(FlagValueType.DOUBLE, type("3.14"));
        assertEquals(FlagValueType.OBJECT, type("{\"max\":10}"));
        assertEquals(FlagValueType.OBJECT, type("[1,2,3]"));
        assertEquals(FlagValueType.STRING, type("hello"));
        assertEquals(FlagValueType.STRING, type("{\"max\":"));
    }

    @Test
    void objectValues() {
        Value value = object("{\"max\":10,\"on\":true}");
        assertTrue(value.isStructure());
        assertEquals(10, value.asStructure().getValue("max").asInteger());
        assertEquals(true, value.asStructure().getValue("on").asBoolean());

        assertTrue(object("{}").isStructure());
    }

    @Test
    void nestedObject() {
        Value value = object("{\"outer\":{\"inner\":\"deep\"}}");
        assertEquals("deep", value.asStructure()
                .getValue("outer").asStructure()
                .getValue("inner").asString());
    }

    @Test
    void arrayValues() {
        Value value = object("[1,2,3]");
        assertTrue(value.isList());
        assertEquals(List.of(new Value(1), new Value(2), new Value(3)), value.asList());
    }

    @Test
    void longInsideObject() {
        Value value = object("{\"max\":10000000000}");
        assertEquals(10_000_000_000L, value.asStructure().getValue("max").asLong());
    }

    // only an object and an array are read as JSON
    @Test
    void notReadAsJson() {
        assertEquals("\"quoted\"", object("\"quoted\"").asString());
        assertEquals("null", object("null").asString());
        assertEquals("{\"max\":", object("{\"max\":").asString());
        assertEquals("hello", object("hello").asString());
        assertEquals("", object("").asString());
    }

    // a flag can be evaluated as a type other than the inferred one

    @Test
    void booleanValues() {
        assertEquals(true, bool("true"));
        assertEquals(false, bool("false"));
        assertEquals(true, bool("TRUE"));
        // anything that is not a boolean is false, never an error
        assertEquals(false, bool("yes"));
        assertEquals(false, bool("1"));
        assertEquals(false, bool(""));
    }

    @Test
    void integerValues() {
        assertEquals(42, integer("42"));
        assertEquals(-42, integer("-42"));
    }

    @Test
    void integerRejectsOtherTypes() {
        // a value that doesn't fit an int must not be truncated
        assertThrows(TypeMismatchError.class, () -> integer("10000000000"));
        // a fractional value must not be truncated either
        assertThrows(TypeMismatchError.class, () -> integer("3.14"));
        assertThrows(TypeMismatchError.class, () -> integer("hello"));
        assertThrows(TypeMismatchError.class, () -> integer("true"));
    }

    @Test
    void longValues() {
        assertEquals(10_000_000_000L, longValue("10000000000"));
        // a value that fits an int is still a valid long
        assertEquals(42L, longValue("42"));
    }

    @Test
    void longRejectsOtherTypes() {
        assertThrows(TypeMismatchError.class, () -> longValue("3.14"));
        assertThrows(TypeMismatchError.class, () -> longValue("hello"));
    }

    @Test
    void doubleValues() {
        assertEquals(3.14, doubleValue("3.14"));
        // every number converts to a double, whether it was written as one or not
        assertEquals(42.0, doubleValue("42"));
        assertEquals(1.0e10, doubleValue("10000000000"));
    }

    @Test
    void doubleRejectsOtherTypes() {
        assertThrows(TypeMismatchError.class, () -> doubleValue("hello"));
        assertThrows(TypeMismatchError.class, () -> doubleValue("true"));
    }

    @Test
    void stringValues() {
        assertEquals("hello", string("hello"));
        assertEquals("", string(""));
    }

    // a flag of any other type can still be read as a string; it comes back in its
    // canonical form, which is the value the configuration meant
    @Test
    void stringOfOtherTypes() {
        assertEquals("42", string("42"));
        assertEquals("10000000000", string("10000000000"));
        assertEquals("3.14", string("3.14"));
        assertEquals("3.1", string("3.10"));
        assertEquals("true", string("true"));
        assertEquals("true", string("TRUE"));
        assertEquals("{\"max\":10}", string("{\"max\":10}"));
        assertEquals("{\"max\":10}", string("{ \"max\": 10 }"));
        assertEquals("[1,2]", string("[1,2]"));
        assertEquals("[1,2]", string("[1, 2]"));
    }

    @Test
    void missingFlag() {
        ConfigFeatureProvider provider = provider("whatever");
        assertThrows(FlagNotFoundError.class, () -> provider.getStringEvaluation("missing", null, null));
        assertThrows(FlagNotFoundError.class, () -> provider.getBooleanEvaluation("missing", null, null));
        assertThrows(FlagNotFoundError.class, () -> provider.getIntegerEvaluation("missing", null, null));
        assertThrows(FlagNotFoundError.class, () -> provider.getLongEvaluation("missing", null, null));
        assertThrows(FlagNotFoundError.class, () -> provider.getDoubleEvaluation("missing", null, null));
        assertThrows(FlagNotFoundError.class, () -> provider.getObjectEvaluation("missing", new Value(), null));
    }
}
