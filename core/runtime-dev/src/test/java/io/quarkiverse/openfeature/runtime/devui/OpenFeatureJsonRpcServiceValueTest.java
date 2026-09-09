package io.quarkiverse.openfeature.runtime.devui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.Value;

public class OpenFeatureJsonRpcServiceValueTest {
    @Test
    public void contextIntStaysInt() {
        EvaluationContext ctx = OpenFeatureJsonRpcService.parseContext("{\"count\":10}");
        assertEquals(10, ctx.getValue("count").asInteger());
    }

    @Test
    public void contextLongIsNotTruncated() {
        EvaluationContext ctx = OpenFeatureJsonRpcService.parseContext("{\"count\":10000000000}");
        assertEquals(10_000_000_000L, ctx.getValue("count").asLong());
    }

    @Test
    public void contextDoubleStaysDouble() {
        EvaluationContext ctx = OpenFeatureJsonRpcService.parseContext("{\"ratio\":3.14}");
        assertEquals(3.14, ctx.getValue("ratio").asDouble());
    }

    @Test
    public void intValueRendersAsInt() {
        assertEquals(10, OpenFeatureJsonRpcService.valueToJson(new Value(10)));
    }

    @Test
    public void longValueRendersAsLong() {
        assertEquals(10_000_000_000L, OpenFeatureJsonRpcService.valueToJson(new Value(10_000_000_000L)));
    }

    @Test
    public void doubleValueRendersAsDouble() {
        assertEquals(3.14, OpenFeatureJsonRpcService.valueToJson(new Value(3.14)));
    }

    @Test
    public void integralDoubleValueRendersAsInt() {
        assertEquals(10, OpenFeatureJsonRpcService.valueToJson(new Value(10.0)));
    }

    @Test
    public void doubleOutOfLongRangeRendersAsDouble() {
        assertEquals(1.0E19, OpenFeatureJsonRpcService.valueToJson(new Value(1.0E19)));
    }
}
