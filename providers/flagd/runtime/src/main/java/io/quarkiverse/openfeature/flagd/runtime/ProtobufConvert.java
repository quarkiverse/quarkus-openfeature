package io.quarkiverse.openfeature.flagd.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.protobuf.Struct;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.MutableStructure;

class ProtobufConvert {
    static EvaluationContext toEvaluationContext(Struct struct) {
        Map<String, dev.openfeature.sdk.Value> values = new HashMap<>();
        struct.getFieldsMap().forEach((key, value) -> values.put(key, toValue(value)));
        return new ImmutableContext(values);
    }

    private static dev.openfeature.sdk.Value toValue(com.google.protobuf.Value protobuf) {
        return switch (protobuf.getKindCase()) {
            case NULL_VALUE -> new dev.openfeature.sdk.Value();
            case BOOL_VALUE -> new dev.openfeature.sdk.Value(protobuf.getBoolValue());
            case STRING_VALUE -> new dev.openfeature.sdk.Value(protobuf.getStringValue());
            case NUMBER_VALUE -> new dev.openfeature.sdk.Value(protobuf.getNumberValue());
            case STRUCT_VALUE -> {
                Map<String, dev.openfeature.sdk.Value> result = new HashMap<>();
                protobuf.getStructValue().getFieldsMap().forEach((key, value) -> result.put(key, toValue(value)));
                yield new dev.openfeature.sdk.Value(new MutableStructure(result));
            }
            case LIST_VALUE -> {
                List<dev.openfeature.sdk.Value> result = new ArrayList<>();
                for (com.google.protobuf.Value value : protobuf.getListValue().getValuesList()) {
                    result.add(toValue(value));
                }
                yield new dev.openfeature.sdk.Value(result);
            }
            case KIND_NOT_SET -> throw new IllegalArgumentException("Unknown protobuf kind: " + protobuf.getKindCase());
        };
    }
}
