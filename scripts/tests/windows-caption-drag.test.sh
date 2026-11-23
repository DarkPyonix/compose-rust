#!/usr/bin/env bash
# Verifies that the Kotlin/Native and GraalVM Windows renderers wire Chrome.FullSize to
# dxc_native_window_configure_app_caption, provide LocalWindowDrag via
# dxc_native_window_begin_drag, and follow the theme colour scheme via
# dxc_native_window_set_color_scheme.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CALLS="$ROOT/renderer/windows/src/Win32Calls.kt"
WIN="$ROOT/renderer/windows/src/Win32Window.kt"
SINGLE="$ROOT/renderer/windows/src/WindowsRenderer.kt"
MULTI="$ROOT/renderer/windows/src/WindowsMultiWindow.kt"
GRAAL="$ROOT/renderer/desktop/src/Win32Window.kt"

grep -q 'dxc_native_window_configure_app_caption' "$CALLS" || { echo "FAIL: Win32Calls.kt missing dxc_native_window_configure_app_caption"; exit 1; }
grep -q 'dxc_native_window_begin_drag' "$CALLS" || { echo "FAIL: Win32Calls.kt missing dxc_native_window_begin_drag"; exit 1; }
grep -q 'configureAppCaption(config.chrome == Chrome.FullSize)' "$WIN" || { echo "FAIL: Win32Window.openOf missing configureAppCaption"; exit 1; }
grep -q 'configureAppCaption(appCaption)' "$WIN" || { echo "FAIL: Win32Window.open missing configureAppCaption"; exit 1; }
grep -q 'fun beginDrag()' "$WIN" || { echo "FAIL: Win32Window missing beginDrag()"; exit 1; }
grep -q 'LocalWindowDrag provides { window.beginDrag() }' "$SINGLE" || { echo "FAIL: WindowsRenderer.kt missing LocalWindowDrag"; exit 1; }
grep -q 'setWindowColorScheme(' "$SINGLE" || { echo "FAIL: WindowsRenderer.kt missing setWindowColorScheme"; exit 1; }
grep -q 'LocalWindowDrag provides { window.beginDrag() }' "$MULTI" || { echo "FAIL: WindowsMultiWindow.kt missing LocalWindowDrag"; exit 1; }
grep -q 'dxc_native_window_configure_app_caption' "$GRAAL" || { echo "FAIL: desktop Win32Window.kt missing dxc_native_window_configure_app_caption"; exit 1; }
grep -q 'configureAppCaption(' "$GRAAL" || { echo "FAIL: desktop Win32Window.kt does not call configureAppCaption"; exit 1; }
grep -q 'LocalWindowDrag provides { beginNativeWindowDrag(0) }' "$GRAAL" || { echo "FAIL: desktop Win32Window.kt missing LocalWindowDrag"; exit 1; }
grep -q 'dxc_native_window_set_color_scheme' "$GRAAL" || { echo "FAIL: desktop Win32Window.kt missing dxc_native_window_set_color_scheme"; exit 1; }
grep -q 'setWindowColorScheme(' "$GRAAL" || { echo "FAIL: desktop Win32Window.kt does not call setWindowColorScheme"; exit 1; }

echo "ok    Windows Kotlin/Native and GraalVM renderers wire Chrome.FullSize app caption, LocalWindowDrag, and colour scheme"
