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
# Not only that the renderer returned 0. The host sends two synthetic clicks once the window has
# composed, so seeing both answered means a Compose scene was made, drew, and took input.
out="$("$host")" || { echo "$out"; exit 1; }
echo "$out"
for needed in "compose_rust_host_init" "dispatch_event: click 2" "compose_rust_renderer_run returned 0"; do
    grep -q "$needed" <<< "$out" || die "the smoke run never printed '$needed'" \
        "The renderer returned without composing, drawing and answering input."
done
