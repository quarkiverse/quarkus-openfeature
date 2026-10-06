# GO Feature Flag WASM Engine

The `gofeatureflag_evaluation.wasi` file is the GO Feature Flag evaluation
engine compiled to WebAssembly (`wasm32-wasip1` target). It is compiled
to Java bytecode during build and executed at runtime by Endive (a pure
Java WASM runtime) to evaluate feature flags in-process.

- Repository: https://github.com/go-feature-flag/wasm-releases
- Version: `0.2.2`
- License: MIT, Copyright (c) 2025 GO Feature Flag

When updating the version, also update `docs/modules/ROOT/pages/gofeatureflag.adoc`
and `src/main/resources/META-INF/NOTICE`, and check whether the license
of the engine has changed.
