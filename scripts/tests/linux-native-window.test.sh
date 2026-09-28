#!/usr/bin/env bash
# The Kotlin/Native Linux window: the module, the reach into X11, and the frame that belongs to
# a resize.
#
# Three things here cannot be seen from this machine. The module cannot be compiled without the
# patched Compose for linuxX64 in the local Maven repository and the X11 development headers
# installed, both of which are the merge's job and neither of which is a minute's work. Its tests
# cannot be run anywhere but Linux, because they are compiled for linuxX64. And whether a dragged
# edge stays attached to what is inside it is a fact about what a compositor showed.
#
# What is left is the wiring, and that is what this reads: that the module exists and is declared,
# that it reaches the interpreter through the same symlinks macOS does, that the window draws the
# frame for a new size from inside the event that recorded it, and that the promise made to the
# window manager is kept in the right order. Every one of those has been got wrong at least once
# on one of the other desktops.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
renderer="$repo_root/dioxus-compose-renderer"
project="$renderer/project.yaml"
module="$renderer/linux/module.yaml"
window="$renderer/linux/src/LinuxWindow.kt"
surface="$renderer/linux/src/GlSurface.kt"
entry="$renderer/linux/src/LinuxRenderer.kt"
definition="$renderer/linux/cinterop/x11.def"
sync_source="$renderer/desktop/src/ResizeSync.kt"
log_source="$renderer/desktop/src/WindowEventLog.kt"
frames_source="$renderer/desktop/src/WindowFrames.kt"
model="$renderer/desktop/src/X11Window.kt"
staticlib="$renderer/staticlib-linux/module.yaml"
compose_script="$renderer/scripts/build-compose.sh"
compose_patch="$renderer/patches/0001-linux-native-targets.patch"

red=0

fail() {
    echo "fail: $1"
    red=1
}

for file in "$project" "$module" "$window" "$surface" "$entry" "$definition" \
            "$sync_source" "$log_source" "$frames_source" "$model" "$staticlib" \
            "$compose_script" "$compose_patch"; do
    [[ -f "$file" ]] || fail "missing $file"
done
(( red == 0 )) || exit 1

# `! grep ...` cannot fail a script, because the shell ignores a failing command preceded by the
# `!` reserved word. Say it the long way.
absent() {
    local pattern="$1" file="$2" why="$3"
    if grep -Eq -- "$pattern" "$file"; then
        fail "$(basename "$file") still matches '$pattern': $why"
    fi
}

# ---------------------------------------------------------------------------
# The module, and the one platform it is for.
# ---------------------------------------------------------------------------

grep -Eq '^ +- linux$' "$project" ||
    fail "project.yaml does not list the linux module, so nothing builds it"
grep -Eq '^ +- staticlib-linux$' "$project" ||
    fail "project.yaml does not list staticlib-linux, so the Host has no symbols to link"
grep -Fq 'platforms: [ linuxX64 ]' "$module" ||
    fail "the linux module does not declare linuxX64"
grep -Fq 'mavenLocal' "$module" ||
    fail "the linux module does not read the local Maven repository, which is the only place the patched Compose for this target is"

# Nothing of GraalVM may reach a Kotlin/Native module: the annotations are not on its classpath and
# there is no isolate for them to describe.
for source in "$renderer"/linux/src/*.kt; do
    absent 'org\.graalvm' "$source" "Kotlin/Native has no native-image annotations"
done

# ---------------------------------------------------------------------------
# One copy of the interpreter, reached the way macOS reaches it.
# ---------------------------------------------------------------------------

missing_links=0
while IFS= read -r shared; do
    name="$(basename "$shared")"
    [[ -L "$renderer/linux/src/shared/$name" ]] || {
        echo "       linux/src/shared/$name is not there"
        missing_links=$((missing_links + 1))
    }
done < <(find "$renderer/macos/src/shared" -maxdepth 1 -name '*.kt')
(( missing_links == 0 )) ||
    fail "$missing_links of the interpreter's sources are not symlinked into the linux module, so it holds a second copy of them or does not compile"

# A copy rather than a link is the failure this is about: two copies of the interpreter drift, and
# the drift is invisible until one platform draws something the other does not.
while IFS= read -r source; do
    [[ -L "$source" ]] ||
        fail "linux/src/shared/$(basename "$source") is a copy rather than a symlink"
done < <(find "$renderer/linux/src/shared" -maxdepth 1 -name '*.kt')

# ---------------------------------------------------------------------------
# The reach into X11.
# ---------------------------------------------------------------------------

# Xext is where the sync counter calls live, and it is easy to miss because everything else under
# X11/extensions is in libX11. Without it the link fails on three symbols and nothing says why.
grep -Eq '^linkerOpts = .*-lXext' "$definition" ||
    fail "the cinterop definition does not link libXext, which is where XSyncSetCounter is"
for library in -lX11 -lGL; do
    grep -Eq "^linkerOpts = .*$library( |\$)" "$definition" ||
        fail "the cinterop definition does not link $library"
done
grep -Fq 'X11/extensions/sync.h' "$definition" ||
    fail "the cinterop definition does not declare the sync extension, so the counter cannot be set"

# The headers are the system's own. A copy of them kept here would be the layout of XEvent and
# XSetWindowAttributes written out by hand, and a field at the wrong offset is a window that
# misbehaves for a reason nobody can see in the source.
grep -Eq '^compilerOpts = .*-idirafter' "$definition" ||
    fail "the cinterop definition searches for the X11 headers with -I rather than -idirafter, which puts the host's libc headers in front of the target's sysroot"

# ---------------------------------------------------------------------------
# Every Compose module this target has to be given is one the build script publishes.
# ---------------------------------------------------------------------------
#
# Compose Multiplatform publishes `runtime` for linuxX64 and nothing else, so every module the
# patch teaches the target has to be built and published here. One left off the list is not a
# failure of that script: it is an unresolvable dependency tens of minutes into the renderer's own
# build, naming a coordinate nobody recognises. `ui-test` is the one exception and is deliberate,
# because nothing the renderer links reaches it.
linux_publications="$(sed -n '/linuxX64)/,/;;/p' "$compose_script")"
while IFS= read -r gradle_path; do
    [[ "$gradle_path" == "compose:ui:ui-test" ]] && continue
    grep -Fq "$gradle_path" <<< "$linux_publications" ||
        fail "the patch adds a linuxX64 target to $gradle_path and build-compose.sh does not publish it"
done < <(grep -E '^\+\+\+ b/.*/build\.gradle$' "$compose_patch" |
    sed -E 's#^\+\+\+ b/##; s#/build\.gradle$##; s#/#:#g' | sort -u)

# ---------------------------------------------------------------------------
# The frame that belongs to a resize is drawn where the resize is handled.
# ---------------------------------------------------------------------------

configure="$(sed -n '/ConfigureNotify -> {/,/^            Expose ->/p' "$window")"
grep -Fq 'frames.draw()' <<< "$configure" ||
    fail "the window records a new size without drawing the frame for it, so the edge moves before the content does"
grep -Fq 'measured = IntSize(width, height)' <<< "$configure" ||
    fail "the window no longer takes the new size from the configure event"

# An uncovered window is redrawn. The loop draws when the scene changed, and being uncovered
# changes nothing in the scene.
grep -Fq 'Expose ->' "$window" || fail "the window ignores being uncovered"

# Events are read in one place, through the guard, because a frame drawn inside a resize draws from
# the middle of reading an event.
grep -Fq 'log.read {' "$window" ||
    fail "the window reads the display server without the guard that stops a read inside a read"
if [[ "$(grep -v '^import ' "$window" | grep -c 'XNextEvent')" != "1" ]]; then
    fail "the window takes events off the queue in more than one place, which is how the rest of a drag gets drained mid-frame"
fi

# The promise to the window manager, and the order it is kept in.
grep -Fq '_NET_WM_SYNC_REQUEST_COUNTER' "$window" ||
    fail "the window offers the manager no counter to hold a resize against"
grep -Fq '_NET_WM_SYNC_REQUEST' "$window" ||
    fail "the window does not offer the manager the sync protocol, so it is never asked"
grep -Fq 'XSyncSetCounter' "$window" ||
    fail "the window never tells the manager the drawing for a new size is done"
grep -Fq 'sync.frameDrawn()' "$window" ||
    fail "the window does not end a frame through ResizeSync, which is what keeps swap-then-tell in that order"
grep -Fq 'sync.noFrame()' "$window" ||
    fail "a request that produced no frame leaves the manager waiting for a counter nobody will set"

# And the order itself, which lives in the class the JVM tests drive.
order="$(sed -n '/fun frameDrawn()/,/^    }/p' "$sync_source" | grep -n -e 'present()' -e 'pay()')"
if [[ "$(sed -n 1p <<< "$order")" != *present* || "$(sed -n 2p <<< "$order")" != *pay* ]]; then
    fail "ResizeSync does not hand the drawing over and then tell the manager, in that order"
fi

# ---------------------------------------------------------------------------
# A turn of the loop does what the other desktops' turns do.
# ---------------------------------------------------------------------------

for call in 'work.runPending()' 'scene.hasInvalidations()' 'semantics.pushIfChanged(afterDrawing'; do
    grep -Fq "$call" "$model" ||
        fail "X11Window.kt no longer does '$call', so this test is comparing against nothing"
    grep -Fq "$call" "$window" || fail "the Linux window does not do '$call' per frame"
done
grep -Fq 'while (!closed)' "$window" ||
    fail "the Linux window does not run until it is closed"
absent 'usleep|sleep\(' "$window" \
    "the waiting belongs in the window's own turn, where an event that arrives ends it"

# The window answers null rather than throwing, because a Kotlin exception crossing back into the
# C entry point that called in is undefined.
grep -Fq 'fun open(title: String, width: Int, height: Int): LinuxWindow?' "$window" ||
    fail "the window does not answer null where there is no display server to open one on"
grep -Fq 'RUN_FAILED' "$entry" ||
    fail "the entry point does not turn a window that would not open into a status code"

# The frame is handed to the server separately from being drawn, because what goes between the two
# is the promise above.
grep -Fq 'glXSwapBuffers' "$surface" ||
    fail "nothing presents the frame to the display server"
absent 'glXSwapBuffers' "$window" \
    "presenting belongs to the surface, so that the order around it stays in one place"
# XSync blocks until the server has caught up. A frame loop that waits for the server is a frame
# loop that has given away the refresh it was trying to keep; XFlush sends and returns.
absent 'XSync\(' "$window" "waiting for the server costs the frame this window exists to save"

(( red == 0 )) || exit 1
echo "ok: the Linux window is a module of its own, reads X11 in one place, and draws its resize inside it"
