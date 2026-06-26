package io.quarkiverse.openfeature.gofeatureflag.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class GoFeatureFlagWasmEngineTest {
    @Test
    public void evaluateManyTimesDoesNotLeakMemory() {
        GoFeatureFlagWasmEngine engine = new GoFeatureFlagWasmEngine();

        String input = "{\"flagKey\":\"test\",\"flag\":{},\"evalContext\":{},\"flagContext\":{}}";

        // warm up to reach steady state
        for (int i = 0; i < 1000; i++) {
            engine.evaluate(input);
        }

        int pagesBefore = engine.memoryPages();
        for (int i = 0; i < 10_000; i++) {
            engine.evaluate(input);
        }
        int pagesAfter = engine.memoryPages();

        assertEquals(pagesBefore, pagesAfter, "WASM memory should not grow when results are properly managed");
    }
}
