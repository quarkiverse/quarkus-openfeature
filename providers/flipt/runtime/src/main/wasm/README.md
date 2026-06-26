# Flipt WASM Engine

The `flipt_engine_wasm.wasm` file is the Flipt evaluation engine compiled to
WebAssembly (`wasm32-wasip1` target). It is compiled to Java bytecode during
build and executed at runtime by Endive (a pure Java WASM runtime) to evaluate
feature flags in-process.

## Source

- Repository: https://github.com/flipt-io/flipt-client-sdks
- Version: `flipt-engine-wasm-v0.3.0`

When updating the version, also update `docs/modules/ROOT/pages/flipt.adoc`.
