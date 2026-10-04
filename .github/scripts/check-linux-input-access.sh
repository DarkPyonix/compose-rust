#!/usr/bin/env bash
# Usage: .github/scripts/check-linux-input-access.sh <static renderer directory> <scratch directory>
#
# Types Korean into the Kotlin/Native Linux window through a real input method, and reads the
# window through a real AT-SPI registry. Needs a display: run it under `xvfb-run -a`. It starts
# its own session bus, so it can run on a machine that has none.
#
#   <static renderer directory>  what build-linux.sh writes (libcompose_rust_renderer.a beside
#                                libcompose_rust_host_exports.so)
#   <scratch directory>          where the application is copied to and its logs are kept
#
# What it proves, and what it does not.
#
# - Accessibility. A registry daemon runs, the window joins it, and a client built on libatspi
#   (what Orca and Accerciser are built on) walks the tree: application, window, a text field,
#   a button named Save with its role, states, extents and action. It presses the button
#   through the Action interface and the Host prints that it was clicked, so the press travelled
#   from the registry through the window's semantics into Compose and over the boundary to the
#   Host. That is everything short of a speech synthesiser reading it aloud.
# - Input. ibus runs with its XIM server and the hangul engine, the window opens an input
#   context, and the keys that spell a Korean word are sent as X key events. The renderer
#   reports what it heard (DXC_REPORT_INPUT) and the Host prints what the field holds. The
#   composition has to arrive as preedit (text the field is still composing) before it arrives
#   as committed text, and the field has to end up holding the word.
#
# What it cannot prove is how any of it looks or sounds, and that GNOME's or KDE's own input
# method and screen reader behave: those are the real-hardware checklist.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <static renderer directory> <scratch directory>" >&2
    exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Its own session bus, started once, so everything below shares it.
if [[ -z "${DXC_INPUT_ACCESS_INNER:-}" ]]; then
    command -v dbus-run-session >/dev/null || { echo "fail  dbus-run-session is not installed" >&2; exit 1; }
    exec env DXC_INPUT_ACCESS_INNER=1 dbus-run-session -- "$0" "$@"
fi

fail() {
    echo "fail  $1" >&2
    shift
    for line in "$@"; do echo "      $line" >&2; done
    show_logs
    exit 1
}

[[ "$(uname -s)" == "Linux" ]] || { echo "fail  this runs on Linux only" >&2; exit 1; }
[[ -n "${DISPLAY:-}" ]] || { echo "fail  DISPLAY is not set; run it under xvfb-run -a" >&2; exit 1; }
for tool in cargo xdotool ibus-daemon ibus python3 gdbus; do
    command -v "$tool" >/dev/null || { echo "fail  $tool is not on PATH" >&2; exit 1; }
done

distribution="$(cd "$1" && pwd)"
[[ -f "$distribution/libcompose_rust_renderer.a" ]] ||
    { echo "fail  $distribution is not a static renderer directory" >&2; exit 1; }
mkdir -p "$2"
scratch="$(cd "$2" && pwd)"
case "$scratch/" in
    "$repo_root"/*) echo "fail  the scratch directory is inside the checkout; use one that is not" >&2; exit 1 ;;
esac
rm -rf "$scratch/input-check"
mkdir -p "$scratch/input-check"
logs="$scratch/input-check"

pids=()
cleanup() {
    for pid in "${pids[@]:-}"; do
        [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
    done
}
trap cleanup EXIT

show_logs() {
    for log in "$logs"/*.log; do
        [[ -f "$log" ]] || continue
        echo "---- $(basename "$log")" >&2
        tail -n 60 "$log" >&2 || true
    done
}

echo "== building the application"
export DXC_LINUX_NATIVE_LIB="$distribution"
CARGO_TARGET_DIR="$repo_root/target/linux-input-check" \
    cargo build --manifest-path "$repo_root/compose-rust/tests/fixtures/consumer/Cargo.toml" >&2
cp "$repo_root/target/linux-input-check/debug/consumer" "$logs/consumer"

echo "== starting the accessibility bus and its registry"
# The session bus starts the accessibility bus the first time it is asked where it is, and the
# accessibility bus starts the registry. Asking here, before the window does, means the window
# does not race a daemon that is still starting.
gdbus call --session --dest org.a11y.Bus --object-path /org/a11y/bus \
    --method org.a11y.Bus.GetAddress >"$logs/a11y-address.log" 2>&1 ||
    fail "the session bus could not start the accessibility bus" \
         "Install at-spi2-core, which ships the service file that does it."
cat "$logs/a11y-address.log"

echo "== starting the input method"
export XMODIFIERS="@im=ibus"
export LANG="${LANG:-C.UTF-8}"
case "$LANG" in *UTF-8*|*utf8*) ;; *) export LANG=C.UTF-8 ;; esac
export LC_ALL="$LANG"
ibus-daemon --xim --panel disable --replace >"$logs/ibus-daemon.log" 2>&1 &
pids+=("$!")
for _ in $(seq 1 40); do
    ibus address >/dev/null 2>&1 && break
    sleep 0.5
done
ibus address >/dev/null 2>&1 || fail "ibus-daemon did not come up"
# The daemon answers `ibus address` before it accepts clients, so selecting the engine is
# retried until it takes.
selected=""
for _ in $(seq 1 30); do
    if ibus engine hangul >"$logs/ibus-engine.log" 2>&1; then selected=1; break; fi
    sleep 1
done
[[ -n "$selected" ]] || fail "the hangul engine could not be selected" "Install ibus-hangul."
echo "-- engine: $(ibus engine)"

echo "== starting the application"
DXC_REPORT_INPUT=1 DXC_REPORT_FRAMES=1 "$logs/consumer" --input-check \
    >"$logs/host.log" 2>"$logs/renderer.log" &
app_pid=$!
pids+=("$app_pid")

window=""
for _ in $(seq 1 120); do
    # The window sets no _NET_WM_PID, so it is found by its name, which is the renderer's default.
    window="$(xdotool search --onlyvisible --name 'compose-rust' 2>/dev/null | head -1 || true)"
    [[ -n "$window" ]] && break
    kill -0 "$app_pid" 2>/dev/null || fail "the application exited before it opened a window"
    sleep 0.5
done
[[ -n "$window" ]] || fail "the application did not open a window"
echo "-- window $window"

echo "== reading the window through AT-SPI"
# Neither check hides the other: a failure here is remembered and the typing check still runs.
accessibility_failed=""
if ! python3 "$repo_root/.github/scripts/atspi-walk.py" consumer 2>&1 | tee "$logs/atspi.log"; then
    accessibility_failed="the window is not readable through AT-SPI"
fi
for _ in $(seq 1 40); do
    grep -q 'input-check: clicked' "$logs/host.log" && break
    sleep 0.25
done
if grep -q 'input-check: clicked' "$logs/host.log"; then
    echo "ok    the button pressed through AT-SPI was clicked in the Host"
else
    accessibility_failed="${accessibility_failed:+$accessibility_failed; }pressing the button through AT-SPI did not reach the Host"
fi

echo "== typing Korean through ibus-hangul"
xdotool windowfocus "$window" || true
# A click in the field, which is at the top of the column: it opens the text input session
# the input method is attached to.
xdotool mousemove --window "$window" 80 28 click 1
sleep 1
# Shift+Space is ibus-hangul's switch between Latin and Hangul. It starts in Latin.
xdotool key --delay 150 shift+space
sleep 0.5
# g k s r m f spells han-geul on a Korean 2-set layout: h a n, g eu l.
xdotool key --delay 200 g k s r m f
sleep 0.5
xdotool key --delay 200 space
sleep 1.5

echo "-- what the renderer heard"
grep -E 'kind=(7|8)' "$logs/renderer.log" || true
echo "-- what the Host heard"
cat "$logs/host.log"

grep -qE 'kind=8,.*text=[^,)]' "$logs/renderer.log" ||
    fail "no composing text reached the window" \
         "The input method was not asked for preedit callbacks, or XIM did not connect." \
         "DISPLAY=$DISPLAY XMODIFIERS=$XMODIFIERS"
grep -q 'kind=7,' "$logs/renderer.log" ||
    fail "no committed text reached the window"
grep -q 'input-check: field = .*한글' "$logs/host.log" ||
    fail "the field did not end up holding the word" \
         "Expected 한글 in what the Host heard the field change to."

[[ -z "$accessibility_failed" ]] || fail "$accessibility_failed"
echo "ok    Korean typed through ibus-hangul reached the field, composed first and committed after"
