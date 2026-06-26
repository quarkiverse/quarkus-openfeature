package io.quarkiverse.openfeature.gofeatureflag.runtime;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import io.quarkiverse.openfeature.gofeatureflag.runtime.wasm.Module;
import run.endive.runtime.ByteArrayMemory;
import run.endive.runtime.ExportFunction;
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportFunction;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.wasi.WasiExitException;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

final class GoFeatureFlagWasmEngine {
    private final Instance instance;
    private final Memory memory;
    private final ExportFunction evaluateFn;
    private final ExportFunction mallocFn;
    private final ExportFunction freeFn;

    GoFeatureFlagWasmEngine() {
        WasiPreview1 wasi = WasiPreview1.builder()
                .withOptions(WasiOptions.builder().build())
                .build();
        // the GOFF WASM binary calls proc_exit(0) during init;
        // replace with a version that only throws on non-zero exit
        List<ImportFunction> hostFunctions = Stream.of(wasi.toHostFunctions())
                .map(hf -> hf.name().equals("proc_exit") ? procExitThatIgnoresZero() : hf)
                .toList();
        this.instance = Instance.builder(Module.load())
                .withMemoryFactory(ByteArrayMemory::new)
                .withMachineFactory(Module::create)
                .withImportValues(ImportValues.builder()
                        .withFunctions(hostFunctions)
                        .build())
                .build();
        this.memory = instance.memory();
        this.evaluateFn = instance.export("evaluate");
        this.mallocFn = instance.export("malloc");
        this.freeFn = instance.export("free");
    }

    String evaluate(String inputJson) {
        byte[] bytes = inputJson.getBytes(StandardCharsets.UTF_8);
        int ptr = allocate(bytes);
        try {
            long packed = evaluateFn.apply(ptr, bytes.length)[0];
            return readPackedResult(packed);
        } finally {
            deallocate(ptr, bytes.length);
        }
    }

    private int allocate(byte[] data) {
        int ptr = (int) mallocFn.apply(data.length)[0];
        memory.write(ptr, data);
        return ptr;
    }

    private void deallocate(int ptr, int len) {
        freeFn.apply(ptr, len);
    }

    // result memory is managed by TinyGo's GC, not malloc, so must not be freed
    private String readPackedResult(long packed) {
        int resultPtr = (int) ((packed >>> 32) & 0xFFFFFFFFL);
        int resultLen = (int) (packed & 0xFFFFFFFFL);
        return memory.readString(resultPtr, resultLen);
    }

    // visible for testing
    int memoryPages() {
        return memory.pages();
    }

    private static ImportFunction procExitThatIgnoresZero() {
        return new HostFunction(
                "wasi_snapshot_preview1",
                "proc_exit",
                FunctionType.accepting(ValType.I32),
                (inst, args) -> {
                    if ((int) args[0] != 0) {
                        throw new WasiExitException((int) args[0]);
                    }
                    return null;
                });
    }
}
