package io.quarkiverse.openfeature.flagd.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.Structure;

public class ProtobufConvertTest {
    @Test
    public void emptyStruct() {
        EvaluationContext context = ProtobufConvert.toEvaluationContext(Struct.getDefaultInstance());

        assertTrue(context.asMap().isEmpty());
    }

    @Test
    public void scalars() {
        Struct struct = Struct.newBuilder()
                .putFields("null", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build())
                .putFields("bool", Value.newBuilder().setBoolValue(true).build())
                .putFields("string", Value.newBuilder().setStringValue("hello").build())
                .putFields("number", Value.newBuilder().setNumberValue(42.5).build())
                .build();

        EvaluationContext context = ProtobufConvert.toEvaluationContext(struct);

        assertEquals(Set.of("null", "bool", "string", "number"), context.asMap().keySet());
        assertTrue(context.getValue("null").isNull());
        assertEquals(Boolean.TRUE, context.getValue("bool").asBoolean());
        assertEquals("hello", context.getValue("string").asString());
        assertEquals(42.5, context.getValue("number").asDouble());
    }

    // protobuf has a single numeric kind, so an integral value also arrives as a double
    @Test
    public void integralNumberBecomesDouble() {
        Struct struct = Struct.newBuilder()
                .putFields("number", Value.newBuilder().setNumberValue(7).build())
                .build();

        dev.openfeature.sdk.Value value = ProtobufConvert.toEvaluationContext(struct).getValue("number");

        assertTrue(value.isNumber());
        assertEquals(7.0, value.asDouble());
        assertEquals(7, value.asInteger());
    }

    @Test
    public void emptyStringAndFalseArePreserved() {
        Struct struct = Struct.newBuilder()
                .putFields("string", Value.newBuilder().setStringValue("").build())
                .putFields("bool", Value.newBuilder().setBoolValue(false).build())
                .build();

        EvaluationContext context = ProtobufConvert.toEvaluationContext(struct);

        assertEquals("", context.getValue("string").asString());
        assertEquals(Boolean.FALSE, context.getValue("bool").asBoolean());
    }

    @Test
    public void list() {
        Struct struct = Struct.newBuilder()
                .putFields("list", Value.newBuilder()
                        .setListValue(ListValue.newBuilder()
                                .addValues(Value.newBuilder().setStringValue("a").build())
                                .addValues(Value.newBuilder().setNumberValue(1).build())
                                .addValues(Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()))
                        .build())
                .putFields("empty", Value.newBuilder().setListValue(ListValue.getDefaultInstance()).build())
                .build();

        EvaluationContext context = ProtobufConvert.toEvaluationContext(struct);

        List<dev.openfeature.sdk.Value> list = context.getValue("list").asList();
        assertEquals(3, list.size());
        assertEquals("a", list.get(0).asString());
        assertEquals(1.0, list.get(1).asDouble());
        assertTrue(list.get(2).isNull());

        assertTrue(context.getValue("empty").asList().isEmpty());
    }

    @Test
    public void nestedStructsAndLists() {
        Struct struct = Struct.newBuilder()
                .putFields("outer", Value.newBuilder()
                        .setStructValue(Struct.newBuilder()
                                .putFields("inner", Value.newBuilder()
                                        .setListValue(ListValue.newBuilder()
                                                .addValues(Value.newBuilder()
                                                        .setStructValue(Struct.newBuilder()
                                                                .putFields("deep",
                                                                        Value.newBuilder().setStringValue("value").build()))
                                                        .build()))
                                        .build()))
                        .build())
                .build();

        Structure outer = ProtobufConvert.toEvaluationContext(struct).getValue("outer").asStructure();
        List<dev.openfeature.sdk.Value> inner = outer.getValue("inner").asList();

        assertEquals(1, inner.size());
        assertEquals("value", inner.get(0).asStructure().getValue("deep").asString());
    }

    @Test
    public void emptyNestedStruct() {
        Struct struct = Struct.newBuilder()
                .putFields("empty", Value.newBuilder().setStructValue(Struct.getDefaultInstance()).build())
                .build();

        Structure empty = ProtobufConvert.toEvaluationContext(struct).getValue("empty").asStructure();

        assertTrue(empty.asMap().isEmpty());
    }

    // an unset field deserializes to a `Value` without kind,
    // the conversion has to reject it rather than silently drop the key
    @Test
    public void unsetKindIsRejected() {
        Struct struct = Struct.newBuilder()
                .putFields("broken", Value.getDefaultInstance())
                .build();

        assertThrows(IllegalArgumentException.class, () -> ProtobufConvert.toEvaluationContext(struct));
    }

    @Test
    public void unsetKindNestedInAListIsRejected() {
        Struct struct = Struct.newBuilder()
                .putFields("list", Value.newBuilder()
                        .setListValue(ListValue.newBuilder().addValues(Value.getDefaultInstance()))
                        .build())
                .build();

        assertThrows(IllegalArgumentException.class, () -> ProtobufConvert.toEvaluationContext(struct));
    }
}
