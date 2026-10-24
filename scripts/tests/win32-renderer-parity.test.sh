#!/usr/bin/env bash
# The two Windows renderers, the native image (GraalVM) and Kotlin/Native, draw through one
# window: the same C file, the same resize decisions in it, asked for the same way.
#
# win32_window.c decides how a size change is drawn (CPU frames copied in, or GPU frames),
# and each renderer only opts in and draws what it is handed. A second copy of that C, or a
# renderer that stopped opting in, would let one path change while the other kept its old
# behaviour, and nothing in a single build would say so. Nothing here needs Windows: it reads
# the two builds, the two Kotlin files and the fork's C at the pinned commit.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

# The window's C is the Compose fork's windows-shared module, at the commit build-compose.sh pins.
source "$repo_root/scripts/tests/fork-window.sh"
fork_window_or_skip "$repo_root"
window_c="$fork_window/windows-shared/c/win32_window.c"
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

# One window file, the fork's, and both builds compile it. A copy back in this tree would be a
# second window that drifts from the first.
copies="$(git ls-files | grep -c '/win32_window\.c$')"
[ "$copies" = 0 ] || fail "there are $copies copies of win32_window.c in this tree; both renderers compile the fork's"
grep -qF 'extended\window\windows-shared\c' "$graal_build" &&
    grep -qF '$WindowSource = Join-Path $WindowCDir "win32_window.c"' "$graal_build" ||
    fail "the native image build no longer compiles the fork's windows-shared/c/win32_window.c"
grep -q 'compile "\$c_dir/win32_window.c"' "$kn_build" ||
    fail "the Kotlin/Native build no longer compiles win32_window.c"
grep -qE '^c_dir=.*fetch-fork-window\.sh.*/extended/window/windows-shared/c"' "$kn_build" ||
    fail "the Kotlin/Native build's c_dir is not the fork's extended/window/windows-shared/c"

# The resize decisions live in the C, and both renderers reach the same entry points: the native
# image by @CFunction, Kotlin/Native through the fork's Win32Loop.
for symbol in dxc_native_set_raster_resize dxc_native_frame_mode dxc_native_raster_begin dxc_native_raster_end; do
    grep -qE "^(int32_t|void) $symbol\(" "$window_c" || fail "$window_c no longer defines $symbol"
    grep -q "@CFunction(\"$symbol\")" "$graal_kotlin" || fail "the native image renderer does not call $symbol"
done
for call in 'Win32Loop.setRasterResize(' 'Win32Loop.rasterFrameWanted' 'Win32Loop.beginRasterFrame()' 'Win32Loop.endRasterFrame()'; do
    grep -qF "$call" "$kn_kotlin" || fail "the Kotlin/Native renderer does not call $call"
done

# Both opt in the same way: CPU resize frames unless DXC_RASTER_RESIZE=0.
grep -qF 'setRasterResize(if (System.getenv("DXC_RASTER_RESIZE") == "0") 0 else 1)' "$graal_kotlin" ||
    fail "the native image renderer does not opt into CPU resize frames the same way"
grep -qF 'Win32Loop.setRasterResize(System.getenv("DXC_RASTER_RESIZE") != "0")' "$kn_kotlin" ||
    fail "the Kotlin/Native renderer does not opt into CPU resize frames the same way"

# Both draw a CPU frame when the window asks for one.
grep -qE 'if \(window\.rasterFrame\(\)\) drawRasterFrame' "$graal_kotlin" ||
    fail "the native image renderer does not draw CPU frames when the window asks"
grep -qE 'if \(Win32Loop\.rasterFrameWanted\) drawRasterFrame' "$kn_kotlin" ||
    fail "the Kotlin/Native renderer does not draw CPU frames when the window asks"

# Keys: one table, which the Kotlin/Native renderer links rather than copies.
link="renderer/windows/src/shared/WindowEvents.kt"
if [ ! -L "$link" ] || [ "$(readlink "$link")" != "../../../desktop/src/WindowEvents.kt" ]; then
    fail "$link is not a link to the native image's WindowEvents.kt, so the two key tables can drift"
fi

[ $status -eq 0 ] && echo "ok: both Windows renderers compile the fork's one win32_window.c and opt into its resize frames the same way"
exit $status
