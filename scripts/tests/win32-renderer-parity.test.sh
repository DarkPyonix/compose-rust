#!/usr/bin/env bash
# The two Windows renderers, the native image (GraalVM) and Kotlin/Native, draw through one
# window: the same C file, the same resize decisions in it, asked for the same way.
#
# win32_window.c decides how a size change is drawn (CPU frames copied in, or GPU frames),
# and each renderer only opts in and draws what it is handed. A second copy of that C, or a
# renderer that stopped opting in, would let one path change while the other kept its old
# behaviour, and nothing in a single build would say so. Nothing here needs Windows: it reads
# the two builds and the two Kotlin files.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

window_c="renderer/desktop/c/win32_window.c"
graal_build="renderer/desktop/scripts/build-native-windows.ps1"
kn_build="renderer/desktop/scripts/build-windows.sh"
graal_kotlin="renderer/desktop/src/Win32Window.kt"
kn_kotlin="renderer/windows/src/Win32Window.kt"
status=0

fail() {
    echo "FAIL: $1"
    status=1
}

for file in "$window_c" "$graal_build" "$kn_build" "$graal_kotlin" "$kn_kotlin"; do
    [ -f "$file" ] || { echo "FAIL: $file is missing"; exit 1; }
done

# One window file in the tree, and both builds compile it.
copies="$(git ls-files | grep -c '/win32_window\.c$')"
[ "$copies" = 1 ] || fail "there are $copies copies of win32_window.c; both renderers must compile the one file"
grep -q 'Join-Path \$NativeDir "c\\win32_window.c"' "$graal_build" ||
    fail "the native image build no longer compiles renderer/desktop/c/win32_window.c"
grep -q 'compile "\$c_dir/win32_window.c"' "$kn_build" ||
    fail "the Kotlin/Native build no longer compiles renderer/desktop/c/win32_window.c"
grep -qE '^c_dir=.*desktop/c' "$kn_build" ||
    fail "the Kotlin/Native build's c_dir is not renderer/desktop/c"

# The resize decisions live in the C, and both renderers reach the same entry points.
for symbol in dxc_native_set_raster_resize dxc_native_frame_mode dxc_native_raster_begin dxc_native_raster_end; do
    grep -qE "^(int32_t|void) $symbol\(" "$window_c" || fail "$window_c no longer defines $symbol"
    grep -q "@CFunction(\"$symbol\")" "$graal_kotlin" || fail "the native image renderer does not call $symbol"
    grep -q "@SymbolName(\"$symbol\")" "$kn_kotlin" || fail "the Kotlin/Native renderer does not call $symbol"
done

# Both opt in the same way: CPU resize frames unless DXC_RASTER_RESIZE=0.
opt_in='setRasterResize(if (System.getenv("DXC_RASTER_RESIZE") == "0") 0 else 1)'
grep -qF "$opt_in" "$graal_kotlin" || fail "the native image renderer does not opt into CPU resize frames the same way"
grep -qF "$opt_in" "$kn_kotlin" || fail "the Kotlin/Native renderer does not opt into CPU resize frames the same way"

# Both draw a CPU frame when the window asks for one.
grep -qE 'if \(window\.rasterFrame\(\)\) drawRasterFrame' "$graal_kotlin" ||
    fail "the native image renderer does not draw CPU frames when the window asks"
grep -qE 'if \(frameMode\(\) != 0\) drawRasterFrame' "$kn_kotlin" ||
    fail "the Kotlin/Native renderer does not draw CPU frames when the window asks"

# Keys: one table, which the Kotlin/Native renderer links rather than copies.
link="renderer/windows/src/shared/WindowEvents.kt"
if [ ! -L "$link" ] || [ "$(readlink "$link")" != "../../../desktop/src/WindowEvents.kt" ]; then
    fail "$link is not a link to the native image's WindowEvents.kt, so the two key tables can drift"
fi

[ $status -eq 0 ] && echo "ok: both Windows renderers compile one win32_window.c and opt into its resize frames the same way"
exit $status
