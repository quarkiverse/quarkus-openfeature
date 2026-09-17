package io.quarkiverse.openfeature.flipt.runtime;

import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.openfeature.runtime.Pool;

final class FliptWasmEnginePool {
    private final Pool<FliptWasmEngine> pool;
    private volatile String latestSnapshot;

    FliptWasmEnginePool(int size, String namespace, String initialSnapshot, ObjectMapper mapper) {
        this.latestSnapshot = initialSnapshot;
        this.pool = new Pool<>("Flipt WASM engine", size, () -> {
            FliptWasmEngine engine = new FliptWasmEngine(mapper);
            engine.initialize(namespace, latestSnapshot);
            return engine;
        }, FliptWasmEngine::destroy);
    }

    String getLatestSnapshot() {
        return latestSnapshot;
    }

    void updateSnapshot(String snapshotJson) {
        this.latestSnapshot = snapshotJson;
    }

    String evaluateBoolean(String requestJson) {
        return evaluate(engine -> engine.evaluateBoolean(requestJson));
    }

    String evaluateVariant(String requestJson) {
        return evaluate(engine -> engine.evaluateVariant(requestJson));
    }

    void close() {
        pool.close();
    }

    private String evaluate(Function<FliptWasmEngine, String> action) {
        return pool.withInstance(engine -> {
            engine.updateIfNecessary(latestSnapshot);
            return action.apply(engine);
        });
    }
}
