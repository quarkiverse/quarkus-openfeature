package io.quarkiverse.openfeature.flipt.runtime;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.openfeature.flipt.runtime.wasm.Module;
import run.endive.runtime.ByteArrayMemory;
import run.endive.runtime.ExportFunction;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;

final class FliptWasmEngine {
    private static final Logger log = Logger.getLogger(FliptWasmEngine.class);

    private final ObjectMapper mapper;
    private final Instance instance;
    private final Memory memory;
    private final ExportFunction allocateFn;
    private final ExportFunction deallocateFn;
    private final ExportFunction initializeEngineFn;
    private final ExportFunction snapshotFn;
    private final ExportFunction evaluateBooleanFn;
    private final ExportFunction evaluateVariantFn;
    private final ExportFunction destroyEngineFn;

    private long enginePtr;
    private String lastAppliedSnapshot;

    FliptWasmEngine(ObjectMapper mapper) {
        this.mapper = mapper;
        WasiPreview1 wasi = WasiPreview1.builder()
                .withOptions(WasiOptions.builder().build())
                .build();
        this.instance = Instance.builder(Module.load())
                .withMemoryFactory(ByteArrayMemory::new)
                .withMachineFactory(Module::create)
                .withImportValues(ImportValues.builder()
                        .withFunctions(List.of(wasi.toHostFunctions()))
                        .build())
                .build();
        this.memory = instance.memory();
        this.allocateFn = instance.export("allocate");
        this.deallocateFn = instance.export("deallocate");
        this.initializeEngineFn = instance.export("initialize_engine");
        this.snapshotFn = instance.export("snapshot");
        this.evaluateBooleanFn = instance.export("evaluate_boolean");
        this.evaluateVariantFn = instance.export("evaluate_variant");
        this.destroyEngineFn = instance.export("destroy_engine");
    }

    void initialize(String namespace, String snapshot) {
        byte[] nsBytes = namespace.getBytes(StandardCharsets.UTF_8);
        byte[] payloadBytes = snapshot.getBytes(StandardCharsets.UTF_8);

        int nsPtr = allocate(nsBytes);
        int payloadPtr = allocate(payloadBytes);
        try {
            long[] result = initializeEngineFn.apply(nsPtr, nsBytes.length, payloadPtr, payloadBytes.length);
            enginePtr = result[0];
            if (enginePtr == 0) {
                throw new RuntimeException("Failed to initialize Flipt WASM engine");
            }
            lastAppliedSnapshot = snapshot;
        } finally {
            deallocate(nsPtr, nsBytes.length);
            deallocate(payloadPtr, payloadBytes.length);
        }
    }

    void updateIfNecessary(String snapshot) {
        // intentional reference equality
        if (lastAppliedSnapshot == snapshot) {
            return;
        }

        byte[] bytes = snapshot.getBytes(StandardCharsets.UTF_8);
        int ptr = allocate(bytes);
        try {
            long packed = snapshotFn.apply(enginePtr, ptr, bytes.length)[0];
            String response = readPackedResult(packed);
            try {
                JsonNode node = mapper.readTree(response);
                if (!"success".equals(node.path("status").asText())) {
                    log.error("Flipt snapshot update failed: " + node.path("error_message").asText());
                }
            } catch (JsonProcessingException e) {
                log.error("Wrong response from Flipt snapshot update", e);
            }
            lastAppliedSnapshot = snapshot;
        } finally {
            deallocate(ptr, bytes.length);
        }
    }

    String evaluateBoolean(String requestJson) {
        return callEvalFunction(evaluateBooleanFn, requestJson);
    }

    String evaluateVariant(String requestJson) {
        return callEvalFunction(evaluateVariantFn, requestJson);
    }

    void destroy() {
        if (enginePtr != 0) {
            destroyEngineFn.apply(enginePtr);
            enginePtr = 0;
        }
    }

    private String callEvalFunction(ExportFunction fn, String requestJson) {
        byte[] bytes = requestJson.getBytes(StandardCharsets.UTF_8);
        int ptr = allocate(bytes);
        try {
            long packed = fn.apply(enginePtr, ptr, bytes.length)[0];
            return readPackedResult(packed);
        } finally {
            deallocate(ptr, bytes.length);
        }
    }

    private int allocate(byte[] data) {
        int ptr = (int) allocateFn.apply(data.length)[0];
        memory.write(ptr, data);
        return ptr;
    }

    private void deallocate(int ptr, int len) {
        deallocateFn.apply(ptr, len);
    }

    private String readPackedResult(long packed) {
        int resultPtr = (int) ((packed >>> 32) & 0xFFFFFFFFL);
        int resultLen = (int) (packed & 0xFFFFFFFFL);
        String result = memory.readString(resultPtr, resultLen);
        deallocate(resultPtr, resultLen);
        return result;
    }

    // visible for testing
    int memoryPages() {
        return memory.pages();
    }
}
