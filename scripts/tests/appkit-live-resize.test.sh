#!/usr/bin/env bash
# While the reader drags an edge of the AppKit window, AppKit stays inside a tracking loop of
# its own and only calls the view back, so the renderer's frame loop is not running. A size
# written down in that callback and left for the loop is a size nothing draws: the layer shows
# the last frame scaled to the new size for the whole drag, which is a stretched picture.
#
# The cure is to draw inside the callback, at the size the view just became, and to present
# inside the window's own transaction. Two halves are checked here, and neither needs a window.
#
# The accounting in `c/appkit_resize.h` is what the window asks of itself, and it is compiled
# with whatever C compiler is here and fed the sizes and presents a drag produces: every size
# followed by a frame at that size is a drag with nothing stretched and nothing stale; a size
# with no frame is a stretched one; a frame at the previous size is a stale one.
#
# The wiring is read out of the source: that the size callback sets the layer's drawable size
# and then draws, that a live resize presents with the transaction, and that every presented
# frame is counted against the size the view has. A run of the real window is
# `DXC_SYNTH=resize DXC_REPORT_RESIZE=1` on any application, and it must say stale=0 and
# stretched=0.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
c_dir="$repo_root/renderer/desktop/c"
header="$c_dir/appkit_resize.h"
source_file="$c_dir/appkit_window.m"
status=0

fail() { echo "FAIL: $1"; status=1; }

[[ -f "$header" && -f "$source_file" ]] || { echo "FAIL: the resize files are missing"; exit 1; }

compiler="${CC:-cc}"
if command -v "$compiler" >/dev/null 2>&1; then
    work="$(mktemp -d "$repo_root/.appkit-live-resize-test.XXXXXX")"
    trap 'rm -rf "$work"' EXIT
    cat > "$work/probe.c" <<'PROBE'
#include <stdio.h>
#include "appkit_resize.h"

int main(void) {
    struct dxc_resize_stats followed, ignored, late;

    dxc_resize_reset(&followed);
    for (int step = 1; step <= 60; step++) {
        dxc_resize_step(&followed, 480 + step * 2, 640 + step * 2);
        dxc_resize_present(&followed, 480 + step * 2, 640 + step * 2);
    }
    printf("followed %lld %lld %lld %lld\n", (long long)followed.steps,
           (long long)followed.presented, (long long)followed.stale,
           (long long)dxc_resize_stretched(&followed));

    dxc_resize_reset(&ignored);
    for (int step = 1; step <= 60; step++) {
        dxc_resize_step(&ignored, 480 + step * 2, 640 + step * 2);
    }
    printf("ignored %lld %lld %lld %lld\n", (long long)ignored.steps,
           (long long)ignored.presented, (long long)ignored.stale,
           (long long)dxc_resize_stretched(&ignored));

    dxc_resize_reset(&late);
    dxc_resize_step(&late, 500, 700);
    dxc_resize_present(&late, 480, 640);
    printf("late %lld %lld %lld %lld\n", (long long)late.steps, (long long)late.presented,
           (long long)late.stale, (long long)dxc_resize_stretched(&late));
    return 0;
}
PROBE
    if ! "$compiler" -I "$c_dir" -o "$work/probe" "$work/probe.c" 2> "$work/cc.log"; then
        cat "$work/cc.log"
        fail "the accounting does not compile"
    else
        out="$("$work/probe")"
        [[ "$(grep '^followed' <<< "$out")" == "followed 60 60 0 0" ]] ||
            fail "a drag with a frame at every size should count nothing stretched or stale: $out"
        [[ "$(grep '^ignored' <<< "$out")" == "ignored 60 0 0 60" ]] ||
            fail "a drag with no frames should count every size stretched: $out"
        [[ "$(grep '^late' <<< "$out")" == "late 1 1 1 1" ]] ||
            fail "a frame at the previous size should count stale and leave the size stretched: $out"
    fi
else
    echo "skipped the accounting: no C compiler"
fi

# What a message does, between its definition and the closing brace at the margin.
body() {
    awk -v pattern="$1" '
        index($0, pattern) { inside = 1 }
        inside { print }
        inside && /^}/ { exit }
    ' "$source_file"
}

resized="$(body '- (void)setFrameSize:(NSSize)size')"
grep -q 'layer.drawableSize' <<< "$resized" || fail "setFrameSize does not set the layer's drawable size"
grep -q 'dxc_draw_frame(dxc_draw_thread)' <<< "$resized" ||
    fail "setFrameSize does not draw a frame at the size it just took"
before="$(sed -n '/layer.drawableSize/=' <<< "$resized" | head -1)"
after="$(sed -n '/dxc_draw_frame(dxc_draw_thread)/=' <<< "$resized" | head -1)"
[[ -n "$before" && -n "$after" && "$before" -lt "$after" ]] ||
    fail "setFrameSize draws before it has told the layer the new size"

grep -q 'viewWillStartLiveResize' "$source_file" && grep -q 'presentsWithTransaction = !dxc_legacy_resize()' "$source_file" ||
    fail "a live resize does not present with the transaction"
grep -q 'viewDidEndLiveResize' "$source_file" && grep -q 'presentsWithTransaction = NO' "$source_file" ||
    fail "the end of a live resize does not go back to presenting on its own"

ended="$(body 'void dxc_native_frame_end')"
grep -q 'waitUntilScheduled' <<< "$ended" && grep -q '\[drawable present\]' <<< "$ended" ||
    fail "a frame presented with the transaction is not presented after its work is scheduled"
grep -q 'dxc_resize_present' <<< "$ended" || fail "presented frames are not counted against the view's size"

grep -q 'dxc_native_set_draw_callback' "$source_file" && ! grep -A4 'void dxc_native_set_draw_callback' "$source_file" | grep -q '(void)callback' ||
    fail "the draw callback is a stub, so nothing draws during a drag"

[[ $status -eq 0 ]] && echo "the AppKit window draws inside a live resize: ok"
exit $status
