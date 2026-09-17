package io.quarkiverse.openfeature.gofeatureflag.runtime;

import io.quarkiverse.openfeature.runtime.Pool;

final class GoFeatureFlagWasmEnginePool {
    private final Pool<GoFeatureFlagWasmEngine> pool;

    GoFeatureFlagWasmEnginePool(int size) {
        // the GOFF WASM engine holds no native resources, it is reclaimed by the GC
        this.pool = new Pool<>("GO Feature Flag WASM engine", size, GoFeatureFlagWasmEngine::new,
                engine -> {
                });
    }

    String evaluate(String inputJson) {
        return pool.withInstance(engine -> engine.evaluate(inputJson));
    }

    void close() {
        pool.close();
    }
}
