#!/usr/bin/env bash
# The X11 window's loop, and the frame that belongs to a resize.
#
# Two things here cannot be seen from this machine, which has no display server in reach:
# that the loop runs until the window closes and does per turn what the other desktops' do,
# and that the frame for a new size is produced inside the handling of the size change
# rather than on the next turn. The size a frame is drawn at and the refusal of a second
# frame while one is running are checked by WindowFramesTest; what is left is the wiring
# between the two sides, which is what this reads.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
window_source="$repo_root/dioxus-compose-renderer/desktop/c/x11_window.c"
loop_file="$repo_root/dioxus-compose-renderer/desktop/src/X11Window.kt"
callback_file="$repo_root/dioxus-compose-renderer/desktop/src/X11FrameCallback.kt"
frames_file="$repo_root/dioxus-compose-renderer/desktop/src/WindowFrames.kt"
model_file="$repo_root/dioxus-compose-renderer/desktop/src/AppKitWindow.kt"
build_script="$repo_root/dioxus-compose-renderer/desktop/scripts/build-native-linux.sh"
for file in "$window_source" "$loop_file" "$callback_file" "$frames_file" "$model_file" \
            "$build_script"; do
    [[ -f "$file" ]] || { echo "missing $file"; exit 1; }
done
red=0

fail() {
    echo "fail: $1"
    red=1
}

# `! grep ...` cannot fail a script, because the shell ignores a failing command preceded by
# the `!` reserved word. Say it the long way.
absent() {
    local pattern="$1" file="$2" why="$3"
    if grep -Eq -- "$pattern" "$file"; then
        fail "$(basename "$file") still matches '$pattern': $why"
    fi
}

# The loop runs until the window closes. A count of frames is a window that goes away while
# someone is using it, which is what this replaced.
grep -Fq 'while (!isWindowClosed())' "$loop_file" ||
    fail "X11Window.kt does not run until the window closes"
absent 'SPIKE_FRAMES' "$loop_file" "a loop that stops after so many frames is not a loop"
absent 'Thread\.sleep' "$loop_file" \
    "the waiting belongs in the window's own turn, where an event that arrives ends it"

# What a turn of the loop does, taken from the loop the other desktop already has. Each of
# these is a defect that has been found on that one: a window that hears nothing, a list
# whose rows are fetched on a thread with no Host, a screen redrawn sixty times a second
# while an input method is trying to reach the process, a tree nobody was told about.
for call in 'pumpWindowEvents(' 'work.runPending()' 'drainWindowEvents()' \
            'scene.hasInvalidations()' 'semantics.pushIfChanged(afterDrawing'; do
    grep -Fq "$call" "$model_file" ||
        fail "AppKitWindow.kt no longer does '$call', so this test is comparing against nothing"
    grep -Fq "$call" "$loop_file" || fail "the X11 loop does not do '$call' per frame"
done

# The frame that belongs to a resize is drawn where the resize is handled. The size being
# written down and the drawing left to the loop is the defect: the display server has moved
# the edge already, so what reaches the screen in between is a strip of window nobody
# painted.
configure="$(sed -n '/case ConfigureNotify:/,/case Expose:/p' "$window_source")"
grep -Fq 'dxc_request_frame();' <<< "$configure" ||
    fail "x11_window.c records a new size without drawing the frame for it"
grep -Fq 'dxc_width = event.xconfigure.width;' <<< "$configure" ||
    fail "x11_window.c no longer takes the new size from the configure event"

# An uncovered window is redrawn. The loop draws when the scene changed, and being uncovered
# changes nothing in the scene.
grep -Fq 'case Expose:' "$window_source" || fail "x11_window.c ignores being uncovered"

# The frame the window asks for is drawn by the renderer, through a pointer it was given.
grep -Fq 'void dxc_native_set_frame_callback(' "$window_source" ||
    fail "x11_window.c has no way to be given the function that draws a frame"
grep -Fq '@CFunction("dxc_native_set_frame_callback")' "$callback_file" ||
    fail "nothing on the Kotlin side registers the frame callback"
grep -Fq 'setX11FramePainter' "$loop_file" || fail "the X11 loop never registers a painter"
grep -Fq 'clearX11FramePainter' "$loop_file" ||
    fail "the X11 loop never takes the painter away, so a closing window can still ask"

# The entry point the pointer points at, and the class it is looked up in. Native Image
# resolves both by name while it builds the image, so a rename that misses one of them is a
# pointer to nothing and a resize that draws nothing.
jvm_name="$(sed -n 's/^@file:JvmName("\([A-Za-z0-9_]*\)")$/\1/p' "$callback_file")"
package="$(sed -n 's/^package \([a-z.]*\)$/\1/p' "$callback_file")"
qualified="$package.$jvm_name"
if [[ -z "$jvm_name" || -z "$package" ]]; then
    fail "X11FrameCallback.kt has no package or no JvmName to resolve the entry point by"
else
    grep -Fq "Class.forName(\"$qualified\")" "$callback_file" ||
        fail "the entry point is looked up in a class other than $qualified"
    # Asked for by name so the flag cannot go on pointing at a class that has been renamed.
    grep -Fq -- "--initialize-at-build-time=$qualified" "$build_script" ||
        fail "the Linux build does not initialise $qualified while it builds the image, so the function pointer stays null"
fi
method="$(sed -n '/CEntryPointLiteral.create(/,/^)/p' "$callback_file" |
    sed -n 's/^ *"\([A-Za-z0-9_]*\)",$/\1/p')"
if [[ -z "$method" ]]; then
    fail "X11FrameCallback.kt does not name the method the pointer points at"
else
    grep -Eq "^fun $method\(" "$callback_file" ||
        fail "the pointer names $method, which is not a top-level function in that file"
    grep -Fq "@CEntryPoint" "$callback_file" || fail "$method is not an entry point"
fi

# Held rather than dropped: the manager hands out a number with a resize and shows the new
# frame when the counter carries it, which is the only thing on this display server that
# puts the moved edge and the drawing inside it on the screen together.
grep -Fq '_NET_WM_SYNC_REQUEST_COUNTER' "$window_source" ||
    fail "the window offers the manager no counter to hold the resize against"
grep -Fq 'XSyncSetCounter' "$window_source" ||
    fail "the window never tells the manager the drawing for a new size is done"
# Told after the frame has been handed over and not before, because what the manager is
# waiting to hear is that the drawing for the size it gave us exists.
swap="$(sed -n '/^void dxc_native_frame_end/,/^}/p' "$window_source" |
    grep -n -e 'glXSwapBuffers' -e 'dxc_pay_sync()')"
if [[ "$(sed -n 1p <<< "$swap")" != *glXSwapBuffers* ||
      "$(sed -n 2p <<< "$swap")" != *dxc_pay_sync* ]]; then
    fail "the frame is not swapped and then the manager told, in that order"
fi
# And told even when no frame came of the request, or the manager holds the window until it
# gives up on it.
request="$(sed -n '/^static void dxc_request_frame/,/^}/p' "$window_source")"
grep -Fq 'dxc_pay_sync();' <<< "$request" ||
    fail "a refused frame leaves the manager waiting for a counter nobody will set"
grep -Fq "'-H:NativeLinkerOption=-lXext'" "$build_script" ||
    fail "the Linux link has no libXext, which is where the counter calls live"

# Every symbol the X11 path calls has somewhere to land. A shared library links with symbols
# it does not have, so a missing one is not a build failure: it is a window that dies the
# first time the pointer crosses into a text field.
for symbol in dxc_native_window_open dxc_native_window_size dxc_native_frame_begin \
              dxc_native_frame_end dxc_native_poll_event dxc_native_pump \
              dxc_native_window_closed dxc_native_set_cursor dxc_native_set_accessibility \
              dxc_native_set_frame_callback; do
    grep -Eq "^(void|int32_t) $symbol\(" "$window_source" ||
        fail "x11_window.c does not define $symbol"
    grep -rqF "@CFunction(\"$symbol\")" "$repo_root/dioxus-compose-renderer/desktop/src" ||
        fail "nothing declares $symbol, so this list has outlived the code"
done

# Where the headers are installed, the window is compiled. Nobody working on this can run it,
# so a type error in it would otherwise be found by whoever builds the renderer.
if cc -fsyntax-only -include X11/Xlib.h -include GL/glx.h -x c /dev/null >/dev/null 2>&1; then
    if ! cc -fsyntax-only -Wall -Wextra "$window_source"; then
        fail "x11_window.c does not compile"
    fi
else
    echo "note: no X11 and GLX headers here, so the window was not compiled"
fi

(( red == 0 )) || exit 1
echo "ok: the X11 loop runs until the window closes and its resize draws its own frame"
