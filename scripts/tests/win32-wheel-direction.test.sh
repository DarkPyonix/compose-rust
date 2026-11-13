#!/usr/bin/env bash
# The wheel on Windows has to scroll the way the reader turns it. Win32 counts a turn of the
# wheel away from the reader as positive, a scroll up; Compose reads a positive
# scrollDelta.y as a scroll down. The pinned fork's window turns one into the other in
# `c/win32_wheel.h`, and this compiles that header with whatever C compiler is here and
# checks the signs and the sizes, including a touchpad's quarter notch. With no compiler,
# only the wiring half runs: that the window procedure answers both wheel messages through
# the header and not with arithmetic of its own.

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/tests/fork-window.sh"
fork_window_or_skip "$repo_root"
header="$fork_window/windows-shared/c/win32_wheel.h"
source_file="$fork_window/windows-shared/c/win32_window.c"

for file in "$header" "$source_file"; do
    [[ -f "$file" ]] || { echo "fail: missing $file (the pinned fork predates the wheel fix)"; exit 1; }
done

if ! grep -q 'dxc_wheel_to_scroll(GET_WHEEL_DELTA_WPARAM' "$source_file"; then
    echo "fail: win32_window.c does not convert the wheel through win32_wheel.h"
    exit 1
fi

compiler=""
if command -v cl.exe >/dev/null 2>&1; then
    compiler="cl"
elif command -v cc >/dev/null 2>&1; then
    compiler="cc"
fi
if [[ -z "$compiler" ]]; then
    echo "ok: the window converts the wheel through win32_wheel.h (no C compiler to check the signs)"
    exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
probe="$work/win32-wheel.c"
cat > "$probe" <<'PROBE'
#include <assert.h>
#include <stdio.h>
#include "win32_wheel.h"

int main(void) {
    float x = 1.0f, y = 1.0f;
    // A notch away from the reader (WM_MOUSEWHEEL +120) is a scroll up: negative y.
    dxc_wheel_to_scroll(120, 0, 3.0f, &x, &y);
    assert(x == 0.0f && y == -3.0f);
    dxc_wheel_to_scroll(-120, 0, 3.0f, &x, &y);
    assert(x == 0.0f && y == 3.0f);
    // A tilt to the right (WM_MOUSEHWHEEL +120) is a positive x.
    dxc_wheel_to_scroll(120, 1, 3.0f, &x, &y);
    assert(x == 3.0f && y == 0.0f);
    // A touchpad's +30 is a quarter notch, not zero.
    dxc_wheel_to_scroll(30, 0, 1.0f, &x, &y);
    assert(x == 0.0f && y == -0.25f);
    dxc_wheel_to_scroll(30, 1, 1.0f, &x, &y);
    assert(x == 0.25f && y == 0.0f);
    printf("ok\n");
    return 0;
}
PROBE

if [[ "$compiler" == "cl" ]]; then
    ( cd "$work" && cl.exe /nologo "/I$(dirname "$header")" \
        /Fe:wheel.exe win32-wheel.c >/dev/null ) \
        || { echo "fail: the wheel conversion did not compile"; exit 1; }
    binary="$work/wheel.exe"
else
    cc -I"$(dirname "$header")" -o "$work/wheel" "$probe" \
        || { echo "fail: the wheel conversion did not compile"; exit 1; }
    binary="$work/wheel"
fi
if ! "$binary" >/dev/null; then
    echo "fail: the wheel would scroll in the wrong direction or lose a touchpad's small steps"
    exit 1
fi

echo "ok: the wheel scrolls the way it is turned, and keeps a touchpad's quarter notch"
