#!/usr/bin/env bash
# Builds the web-interop experiment artifacts into harness/.
# The experiment measures how a Rust (Host) wasm module and a Kotlin/Wasm
# (Renderer) module can share one linear memory and call each other.
# Not wired into scripts/check.sh: this directory is an experiment and nothing
# else may depend on it.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PATH="$HOME/.cargo/bin:$PATH"
out="$here/harness"
mkdir -p "$out"

cd "$here/rust-exporter"

# Variant 1: this module defines and exports `memory`.
cargo build --release --target wasm32-unknown-unknown
cp target/wasm32-unknown-unknown/release/web_interop_rust_exporter.wasm \
   "$out/host_owns_memory.wasm"

# Variant 2: this module imports `env.memory` from whoever instantiates it.
RUSTFLAGS="-Clink-arg=--import-memory" \
  cargo build --release --target wasm32-unknown-unknown --target-dir target-import
cp target-import/wasm32-unknown-unknown/release/web_interop_rust_exporter.wasm \
   "$out/host_imports_memory.wasm"

# The Kotlin/Wasm module.
cd "$here/kotlin-renderer"
./kotlin build
mkdir -p "$out/kotlin"
cp build/artifacts/CompiledWebArtifact/kotlin-rendererwasmJsdebug/kotlin-output/kotlin-renderer.* \
   "$out/kotlin/"

ls -l "$out"/*.wasm "$out"/kotlin/
