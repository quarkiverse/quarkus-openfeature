package io.quarkiverse.openfeature.flipt.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

public class FliptWasmEngineTest {
    @Test
    public void evaluateManyTimesDoesNotLeakMemory() {
        ObjectMapper mapper = new ObjectMapper();
        FliptWasmEngine engine = new FliptWasmEngine(mapper);
        engine.initialize("default", "{\"namespace\":{\"key\":\"default\"},\"flags\":[]}");

        String request = "{\"flag_key\":\"nonexistent\",\"entity_id\":\"\"}";

        // warm up to reach steady state
        for (int i = 0; i < 1000; i++) {
            engine.evaluateBoolean(request);
        }

        int pagesBefore = engine.memoryPages();
        for (int i = 0; i < 10_000; i++) {
            engine.evaluateBoolean(request);
        }
        int pagesAfter = engine.memoryPages();

        assertEquals(pagesBefore, pagesAfter, "WASM memory should not grow when results are properly freed");

        engine.destroy();
    }
}
