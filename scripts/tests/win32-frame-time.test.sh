#!/usr/bin/env bash
# The Windows windows time a frame by the clock, not by counting frames.
#
# They once added a fixed 16 ms to the frame time for every frame painted, so animations ran
# about twice as fast at 120 Hz and 2.3 times as fast at 144 Hz. A test cannot open a window
# here, but it can read the place that decides: each Win32 window must take its frame time
# from the shared FrameClock, and nothing may add a constant step per frame.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
status=0
fail() { echo "FAIL: $1"; status=1; }

for f in renderer/desktop/src/Win32Window.kt renderer/windows/src/Win32Window.kt; do
    file="$repo_root/$f"
    [[ -f "$file" ]] || { fail "$f is missing"; continue; }
    grep -q 'FRAME_NANOS' "$file" && fail "$f still steps the frame time by a constant"
    grep -q 'FrameClock(' "$file" || fail "$f does not read its frame time from a FrameClock"
    grep -q 'frameClock.frameNanos()' "$file" || fail "$f never asks the FrameClock for a frame's time"
done

[[ $status -eq 0 ]] && echo "ok"
exit $status
