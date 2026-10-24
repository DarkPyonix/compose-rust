#!/usr/bin/env bash
# Builds the two Rust wasm variants used by the harness.
#
#   dist/rust_owner.wasm    - defines and exports its own `memory`
#   dist/rust_importer.wasm - imports `env.memory` from the other module
#
# Requires: rustup target add wasm32-unknown-unknown
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here/rust"
mkdir -p "$here/dist"

export PATH="$HOME/.cargo/bin:$PATH"

# Unit tests first (TDD: the arena logic is covered on the host target).
cargo test

# Variant A: this module owns the memory and exports it.
RUSTFLAGS="-C link-arg=--export-memory -C link-arg=--no-entry" \
    cargo build --release --target wasm32-unknown-unknown
cp target/wasm32-unknown-unknown/release/web_interop_rust.wasm "$here/dist/rust_owner.wasm"

# Variant B: this module imports `env.memory` defined elsewhere.
#
# The memory it imports belongs to the Kotlin/Wasm module, whose own data and GC
# shadow stack live at low addresses. `--global-base` moves the whole Rust image
# (data, then stack, then heap) above 16 MiB so the two never overlap. wasm-ld's
# default layout puts the shadow stack *after* the data segments, so nothing of
# Rust's lands below the base.
RUSTFLAGS="-C link-arg=--import-memory -C link-arg=--global-base=16777216 -C link-arg=--no-entry" \
    cargo build --release --target wasm32-unknown-unknown --target-dir target-import
cp target-import/wasm32-unknown-unknown/release/web_interop_rust.wasm "$here/dist/rust_importer.wasm"

ls -l "$here/dist"
