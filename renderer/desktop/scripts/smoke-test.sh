#!/usr/bin/env bash
# Links a minimal C host against the built library and runs it (after build-native.sh).
set -euo pipefail
source "$(dirname "$0")/env.sh"

lib="$DIST_DIR/lib"
[[ -f "$lib/$LIBRARY_NAME.dylib" ]] || die \
    "$lib/$LIBRARY_NAME.dylib not found" \
    "fix: run $(dirname "$0")/build-native.sh first"

host="$BUILD_DIR/smoke_host"
cc -O2 -o "$host" "$NATIVE_DIR/c/smoke_host.c" -L"$lib" -lcompose_rust_renderer -Wl,-rpath,"$lib"
log="$BUILD_DIR/smoke_host.log"
"$host" | tee "$log"
# The host measures its own label twice from inside a frame, once as text and once as the
# node the renderer drew, and the two answers have to be the same numbers.
grep -q "compose_rust_renderer_measure: agree" "$log" || die \
    "the measure round trip did not agree, or never ran; see $log"
