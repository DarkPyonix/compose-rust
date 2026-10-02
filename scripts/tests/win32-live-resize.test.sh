#!/usr/bin/env bash
# While the reader drags the edge of the window on Windows, Windows runs a loop of its own
# inside the handler for the press that began the drag, and the renderer's frame loop does
# not get another turn until the reader lets go. A size written down there and left for the
# next frame is a size nothing draws: for the whole of the drag the window server shows the
# last frame stretched or cut, which is not a lagging window, it is a stopped one.
#
# The cure is to draw inside the resize, and the parts of it that are decisions rather than
# calls are in `c/win32_resize.h`: which size the next frame has to be drawn at, and whether
# a size that has just arrived has to be drawn where it arrived. That header needs no
# Windows and no window, so this compiles it with whatever C compiler is here and checks the
# answers. With no compiler at all, that half skips.
#
# The other half is wiring, and no compiler can check it from a machine with no Windows on
# it: that the two messages which bracket a drag are the ones that set and clear it, that
# the size message notes the size and asks for a frame while a drag is on, and that the
# refit still happens where nothing is holding a buffer. Those are read out of the source.

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
header="$repo_root/dioxus-compose-renderer/desktop/c/win32_resize.h"
source_file="$repo_root/dioxus-compose-renderer/desktop/c/win32_window.c"
red=0

for file in "$header" "$source_file"; do
    [[ -f "$file" ]] || { echo "missing $file"; exit 1; }
done

# Everything between a case label and the answer it gives, so that what a message does can
# be asked about without depending on how it is laid out.
handling() {
    awk -v label="$1" '
        $0 ~ "case " label ":" { inside = 1 }
        inside { print }
        inside && /^        return/ { exit }
    ' "$source_file"
}

expect_in() {
    local what="$1" where="$2" text="$3"
    if ! grep -q "$what" <<< "$text"; then
        echo "fail: $where does not $4"
        red=1
    fi
}

enter="$(handling WM_ENTERSIZEMOVE)"
exit_drag="$(handling WM_EXITSIZEMOVE)"
sized="$(handling WM_SIZE)"

if [[ -z "$enter" || -z "$exit_drag" ]]; then
    echo "fail: the window does not hear the two messages that bracket a drag of its edge"
    echo "      without them a size arriving mid-drag is written down and never drawn"
    red=1
fi
expect_in dxc_resize_begin_drag "the message that starts a drag" "$enter" \
    "record that one has started"
expect_in dxc_resize_end_drag "the message that ends a drag" "$exit_drag" \
    "record that it has ended"
expect_in dxc_resize_note "the size message" "$sized" "write the new size down"
expect_in dxc_resize_draw_here "the size message" "$sized" "ask whether a drag is on"
expect_in dxc_draw_one_frame "the size message" "$sized" \
    "draw a frame where the size arrived"

# The refit itself is not free to move. A swapchain refuses while anything holds one of its
# buffers, so the wait and the release come first; the size the frame is drawn at is
# recorded only once the refit has been accepted.
frame_begin="$(awk '
    /^int32_t dxc_native_frame_begin/ { inside = 1 }
    inside { print NR ": " $0 }
    inside && /^}/ { exit }
' "$source_file")"
line_of() {
    awk -v what="$1" '$0 ~ what { split($0, parts, ":"); print parts[1]; exit }' <<< "$frame_begin"
}
took="$(line_of dxc_resize_take)"
waited="$(line_of dxc_wait_for_gpu)"
released="$(line_of dxc_release_buffers)"
refitted="$(line_of ResizeBuffers)"
recorded="$(line_of dxc_resize_fitted)"
if [[ -z "$took" || -z "$waited" || -z "$released" || -z "$refitted" || -z "$recorded" ]]; then
    echo "fail: the frame no longer takes the size, waits, releases, refits and records"
    red=1
elif (( took > waited || waited > released || released > refitted || refitted > recorded )); then
    echo "fail: a swapchain cannot be refitted while a buffer is held, and the size it was"
    echo "      refitted to is not known before the refit is accepted"
    red=1
fi

if (( red )); then
    exit 1
fi

compiler=""
if command -v cl.exe >/dev/null 2>&1; then
    compiler="cl"
elif command -v cc >/dev/null 2>&1; then
    compiler="cc"
fi
if [[ -z "$compiler" ]]; then
    echo "ok: the window draws where a size arrives (no C compiler to check the arithmetic)"
    exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
probe="$work/win32-live-resize.c"
cat > "$probe" <<'PROBE'
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "win32_resize.h"

int main(void) {
    struct dxc_resize resize;
    int32_t width = 0;
    int32_t height = 0;

    // A window that has just been made and never resized has nothing for a frame to do.
    memset(&resize, 0, sizeof resize);
    dxc_resize_fitted(&resize, 520, 360);
    assert(dxc_resize_take(&resize, &width, &height) == 0);

    // Showing a window reports its size, and it is the size the swapchain already is.
    dxc_resize_note(&resize, 520, 360);
    assert(dxc_resize_take(&resize, &width, &height) == 0);

    // A real resize is taken once, at the size that arrived, and not again.
    dxc_resize_note(&resize, 900, 500);
    assert(dxc_resize_take(&resize, &width, &height) == 1);
    assert(width == 900 && height == 500);
    assert(dxc_resize_take(&resize, &width, &height) == 0);
    dxc_resize_fitted(&resize, 900, 500);
    assert(dxc_resize_take(&resize, &width, &height) == 0);

    // The drag writes a size down on every step of the way, and the frame drawn from the
    // last of them is drawn at the last of them rather than at any it overtook.
    dxc_resize_note(&resize, 901, 500);
    dxc_resize_note(&resize, 950, 520);
    dxc_resize_note(&resize, 1001, 540);
    assert(dxc_resize_take(&resize, &width, &height) == 1);
    assert(width == 1001 && height == 540);

    // A refit that was refused leaves the size as it was, so the window carries on
    // drawing at the size it has and the next one that arrives is taken.
    dxc_resize_note(&resize, 1100, 600);
    assert(dxc_resize_take(&resize, &width, &height) == 1);
    dxc_resize_note(&resize, 1100, 600);
    assert(dxc_resize_take(&resize, &width, &height) == 1);
    assert(width == 1100 && height == 600);

    // A minimised window is not a window of no size, and nothing here is asked to make a
    // swapchain of one.
    memset(&resize, 0, sizeof resize);
    dxc_resize_fitted(&resize, 520, 360);
    dxc_resize_note(&resize, 0, 0);
    assert(dxc_resize_take(&resize, &width, &height) == 0);

    // Outside a drag a size is written down and the frame loop takes it. Inside one there
    // is no frame loop to take it, so it has to be drawn where it arrived.
    memset(&resize, 0, sizeof resize);
    assert(dxc_resize_draw_here(&resize) == 0);
    dxc_resize_begin_drag(&resize);
    assert(dxc_resize_draw_here(&resize) != 0);
    dxc_resize_end_drag(&resize);
    assert(dxc_resize_draw_here(&resize) == 0);

    printf("ok\n");
    return 0;
}
PROBE

if [[ "$compiler" == "cl" ]]; then
    ( cd "$work" && cl.exe /nologo "/I$repo_root/dioxus-compose-renderer/desktop/c" \
        /Fe:resize.exe win32-live-resize.c >/dev/null ) \
        || { echo "fail: the resize arithmetic did not compile"; exit 1; }
    binary="$work/resize.exe"
else
    cc -I"$repo_root/dioxus-compose-renderer/desktop/c" -o "$work/resize" "$probe" \
        || { echo "fail: the resize arithmetic did not compile"; exit 1; }
    binary="$work/resize"
fi
if ! "$binary" >/dev/null; then
    echo "fail: the window would be drawn at the wrong size while its edge is dragged"
    exit 1
fi

echo "ok: the window draws where a size arrives, at the size that arrived"
