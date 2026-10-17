#!/usr/bin/env bash
# Builds the Kotlin/Wasm module and copies its output next to the harness.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here/kotlin"

./kotlin build

out="$here/kotlin/build/tasks/_kotlin-renderer_buildWasmJsAppWasmJsDebug"
mkdir -p "$here/dist"
cp "$out/kotlin-renderer.wasm" "$out/kotlin-renderer.mjs" "$out/kotlin-renderer.import-object.mjs" \
   "$out/kotlin-renderer.js-builtins.mjs" "$here/dist/"
ls -l "$here/dist"
