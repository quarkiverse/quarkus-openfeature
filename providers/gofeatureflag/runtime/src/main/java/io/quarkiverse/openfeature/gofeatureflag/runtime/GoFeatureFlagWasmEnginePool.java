package io.quarkiverse.openfeature.gofeatureflag.runtime;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

final class GoFeatureFlagWasmEnginePool {
    private static final Logger log = Logger.getLogger(GoFeatureFlagWasmEnginePool.class);

    private final BlockingQueue<GoFeatureFlagWasmEngine> pool;
    private volatile boolean closed;

    GoFeatureFlagWasmEnginePool(int size) {
        this.pool = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            pool.add(new GoFeatureFlagWasmEngine());
        }
    }

    String evaluate(String inputJson) {
        GoFeatureFlagWasmEngine engine = borrow();
        try {
            return engine.evaluate(inputJson);
        } finally {
            returnEngine(engine);
        }
    }

    void close() {
        closed = true;
        pool.clear();
    }

    private GoFeatureFlagWasmEngine borrow() {
        try {
            GoFeatureFlagWasmEngine engine = pool.poll(100, TimeUnit.MILLISECONDS);
            if (engine == null) {
                log.error("Timed out waiting for WASM engine instance");
                throw new RuntimeException("Timed out waiting for WASM engine instance");
            }
            return engine;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for WASM engine instance", e);
        }
    }

    private void returnEngine(GoFeatureFlagWasmEngine engine) {
        if (closed) {
            return;
        }
        if (!pool.offer(engine)) {
            // this should never happen
            log.error("Failed to return WASM engine to pool");
        }
    }
}
