#!/usr/bin/env bash
# Several windows in one process on Windows: the wiring of the Win32 window backend, checked where it can be.
#
# The checks themselves run in the Kotlin/Native renderer on a Windows runner
# (renderer/windows/src/Fr53WindowsCheck.kt, started by scripts/check-windows-consumer.sh), and
# nothing here can compile or run that. What this can say is that the pieces point at each
# other: the checks named in the Kotlin are the ones the build script reads back, the renderer
# starts the multi-window loop for mode Windows and leaves a single window alone, and the
# window backend closes only when the application says so.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
src="$repo_root/renderer/windows/src"
red=0
fail() {
    echo "fail: $1"
    red=1
}

for file in Fr53WindowsCheck.kt WindowsWindowBackend.kt WindowsMultiWindow.kt Win32Calls.kt WindowsRenderer.kt Win32Window.kt; do
    [[ -f "$src/$file" ]] || fail "missing renderer/windows/src/$file"
done
(( red == 0 )) || exit 1

# Every check the Kotlin names is read back by the build script, and the lines it prints have
# the shape the script greps.
names="$(grep -oE '"fr53_windows_[a-z0-9_]+"' "$src/Fr53WindowsCheck.kt" | tr -d '"' | sort -u)"
[[ -n "$names" ]] || fail "Fr53WindowsCheck.kt names no fr53_windows check"
for required in \
    fr53_windows_two_window_nodes_open_two_hwnds_and_close_one \
    fr53_windows_closing_one_window_does_not_quit \
    fr53_windows_close_request_reaches_the_app_and_window_stays \
    fr53_windows_position_round_trips; do
    grep -qx "$required" <<< "$names" || fail "Fr53WindowsCheck.kt has no check named $required"
done
grep -Fq 'compose-rust: $name: ok' "$src/Fr53WindowsCheck.kt" ||
    fail "the checks do not print the line check-windows-consumer.sh looks for"
grep -Fq 'COMPOSE_RUST_FR53_WINDOWS_CHECK=1' "$repo_root/scripts/check-windows-consumer.sh" ||
    fail "check-windows-consumer.sh never asks the renderer to run the several-windows checks"
grep -Fq 'Fr53WindowsCheck.kt' "$repo_root/scripts/check-windows-consumer.sh" ||
    fail "check-windows-consumer.sh does not read the check names from Fr53WindowsCheck.kt"

# A single window application keeps the path it had, and an application of several windows
# gets the loop of its own.
grep -Fq 'asked?.mode == WindowMode.Windows) return runMultiWindow(host)' "$src/WindowsRenderer.kt" ||
    fail "the renderer does not start the multi-window loop for SetWindow mode Windows"
grep -Fq 'Win32Window.open(' "$src/WindowsRenderer.kt" ||
    fail "the single window path no longer opens its window with Win32Window.open"
grep -Fq 'runFr53WindowsChecksIfAsked' "$src/WindowsRenderer.kt" ||
    fail "the renderer never runs the several-windows checks when asked"

# Closing is the application's, and so is quitting. A window asks the C side to hold its
# request to close, and destroying one never posts a quit by itself.
grep -Fq 'setCloseDeferred(1)' "$src/Win32Window.kt" ||
    fail "a window of several does not defer the system's request to close to the application"
grep -Fq 'postQuit' "$src/WindowsMultiWindow.kt" ||
    fail "the multi-window loop does not end through the fork's postQuit"
if grep -Eq 'postQuit|dxc_native_post_quit' "$src/WindowsWindowBackend.kt" "$src/Win32Window.kt"; then
    fail "a window posts a quit itself; whether the process ends is the window manager's call"
fi
grep -Fq 'quitPosted()' "$src/WindowsMultiWindow.kt" ||
    fail "the multi-window loop never reads the posted quit"

# The one frame driver: windows do not serve the Host's frame requests themselves.
grep -Fq 'LocalServesFrames provides false' "$src/WindowsMultiWindow.kt" ||
    fail "a window's content serves the Host's frame requests itself, so one frame asks the Host once per window"
grep -Fq 'serveWindowFrames(' "$src/WindowsMultiWindow.kt" ||
    fail "the multi-window loop does not serve frame requests through serveWindowFrames"

# Windows report real activation in a multi-window process, not the constant a single window has.
grep -Fq 'isWindowFocused: Boolean get() = focused.value' "$src/Win32Window.kt" ||
    fail "a window's focus is not read from the activation the C side reports"

if (( red == 0 )); then
    echo "ok    the Windows backend for several windows is wired: checks named and read back, single window unchanged, close and quit left to the application"
fi
exit $red
