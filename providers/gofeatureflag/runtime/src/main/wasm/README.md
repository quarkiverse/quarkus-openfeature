# GO Feature Flag WASM Engine

The `gofeatureflag_evaluation.wasi` file is the GO Feature Flag evaluation
engine compiled to WebAssembly (`wasm32-wasip1` target). It is compiled
to Java bytecode during build and executed at runtime by Endive (a pure
Java WASM runtime) to evaluate feature flags in-process.

## Source

- Repository: https://github.com/go-feature-flag/wasm-releases
- Version: `0.2.2`

When updating the version, also update `docs/modules/ROOT/pages/gofeatureflag.adoc`.
