# Flipt WASM Engine

The `flipt_engine_wasm.wasm` file is the Flipt evaluation engine compiled to
WebAssembly (`wasm32-wasip1` target). It is compiled to Java bytecode during
build and executed at runtime by Endive (a pure Java WASM runtime) to evaluate
feature flags in-process.

- Repository: https://github.com/flipt-io/flipt-client-sdks
- Version: `flipt-engine-wasm-v0.3.0`
- License: MIT, Copyright (c) 2023 Flipt Software Inc.

Note that the Flipt server is licensed differently; only the client SDKs
repository, which this engine comes from, is MIT.

When updating the version, also update `docs/modules/ROOT/pages/flipt.adoc`
and `src/main/resources/META-INF/NOTICE`, and check whether the license
of the engine has changed.
