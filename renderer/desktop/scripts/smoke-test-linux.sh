#!/usr/bin/env bash
# Links and runs the C host against the Linux renderer.
# UNTESTED ON LINUX as of 2026-09-20.
set -euo pipefail
source "$(dirname "$0")/env-linux.sh"

lib="$DIST_DIR/lib"
[[ -f "$lib/$LIBRARY_NAME.so" ]] || die "$lib/$LIBRARY_NAME.so not found" \
    "fix: run $NATIVE_DIR/scripts/build-native-linux.sh first"
[[ -n "${DISPLAY:-}" ]] || die "DISPLAY is not set; AWT's Linux path needs X11" \
    "X11 session: run this from a terminal in that session." \
    "Wayland session: install/start XWayland and confirm DISPLAY is set." \
    "Headless startup smoke only: xvfb-run -a $NATIVE_DIR/scripts/smoke-test-linux.sh"

case "${XMODIFIERS:-}" in
    @im=ibus|@im=fcitx) ;;
    *) echo "warning: XMODIFIERS is '${XMODIFIERS:-unset}'; ibus/fcitx IME is not configured for Java XIM" >&2
       echo "         use XMODIFIERS=@im=ibus or XMODIFIERS=@im=fcitx with a running daemon" >&2 ;;
esac

host="$BUILD_DIR/smoke_host"
cc -O2 -Wl,--export-dynamic -o "$host" "$NATIVE_DIR/c/smoke_host.c" \
    -L"$lib" -lcompose_rust_renderer -Wl,-rpath,"$lib"
# Not only that the renderer returned 0. The host sends two synthetic clicks once the window has
# composed, so seeing both answered means a Compose scene was made, drew, and took input.
out="$("$host")" || { echo "$out"; exit 1; }
echo "$out"
for needed in "compose_rust_host_init" "dispatch_event: click 2" "compose_rust_renderer_run returned 0"; do
    grep -q "$needed" <<< "$out" || die "the smoke run never printed '$needed'" \
        "The renderer returned without composing, drawing and answering input."
done
