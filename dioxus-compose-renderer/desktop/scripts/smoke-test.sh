#!/usr/bin/env bash
# Links a minimal C host against the built library and runs it (after build-native.sh).
set -euo pipefail
source "$(dirname "$0")/env.sh"

lib="$DIST_DIR/lib"
[[ -f "$lib/$LIBRARY_NAME.dylib" ]] || die \
    "$lib/$LIBRARY_NAME.dylib not found" \
    "fix: run $(dirname "$0")/build-native.sh first"

host="$BUILD_DIR/smoke_host"
cc -O2 -o "$host" "$NATIVE_DIR/c/smoke_host.c" -L"$lib" -ldioxus_compose_renderer -Wl,-rpath,"$lib"
"$host"
