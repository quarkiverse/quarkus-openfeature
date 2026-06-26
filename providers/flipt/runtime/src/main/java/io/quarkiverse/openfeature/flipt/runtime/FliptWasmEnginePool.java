package io.quarkiverse.openfeature.flipt.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

final class FliptWasmEnginePool {
    private static final Logger log = Logger.getLogger(FliptWasmEnginePool.class);

    private final BlockingQueue<FliptWasmEngine> pool;
    private volatile String latestSnapshot;
    private volatile boolean closed;

    FliptWasmEnginePool(int size, String namespace, String initialSnapshot, ObjectMapper mapper) {
        this.pool = new ArrayBlockingQueue<>(size);
        this.latestSnapshot = initialSnapshot;
        for (int i = 0; i < size; i++) {
            FliptWasmEngine engine = new FliptWasmEngine(mapper);
            engine.initialize(namespace, initialSnapshot);
            pool.add(engine);
        }
    }

    String getLatestSnapshot() {
        return latestSnapshot;
    }

    void updateSnapshot(String snapshotJson) {
        this.latestSnapshot = snapshotJson;
    }

    String evaluateBoolean(String requestJson) {
        FliptWasmEngine engine = borrow();
        try {
            return engine.evaluateBoolean(requestJson);
        } finally {
            returnEngine(engine);
        }
    }

    String evaluateVariant(String requestJson) {
        FliptWasmEngine engine = borrow();
        try {
            return engine.evaluateVariant(requestJson);
        } finally {
            returnEngine(engine);
        }
    }

    void close() {
        closed = true;
        List<FliptWasmEngine> drained = new ArrayList<>();
        pool.drainTo(drained);
        for (FliptWasmEngine engine : drained) {
            engine.destroy();
        }
    }

    private FliptWasmEngine borrow() {
        try {
            FliptWasmEngine engine = pool.poll(100, TimeUnit.MILLISECONDS);
            if (engine == null) {
                log.error("Timed out waiting for WASM engine instance");
                throw new RuntimeException("Timed out waiting for WASM engine instance");
            }
            engine.updateIfNecessary(latestSnapshot);
            return engine;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for WASM engine instance", e);
        }
    }

    private void returnEngine(FliptWasmEngine engine) {
        if (closed) {
            engine.destroy();
            return;
        }
        if (!pool.offer(engine)) {
            // this should never happen
            log.error("Failed to return WASM engine to pool");
            engine.destroy();
        }
    }
}
