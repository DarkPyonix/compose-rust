#!/usr/bin/env bash
# Every C symbol the desktop renderer names, answered by every desktop it is built for.
#
# One piece of Kotlin drives all three desktops and reaches their windows by name. Exactly
# one of the C files is compiled into an image, so each of them has to answer every name,
# including the ones that mean nothing on it. What "answer" means there is a function that
# does nothing and says why.
#
# This is checked here rather than left to the build because of how it fails. On macOS the
# linker is told to look names up at load time, so a missing one links and the image runs;
# on Linux a shared object does the same. On Windows it is not a warning, it is a DLL that
# does not link, and nobody sees that until a Windows machine tries. Three branches landed
# with the same hole for exactly that reason.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# The AppKit and X11 windows, their C and the @CFunction declarations that reach it, are the
# Compose fork's graalvm modules at the pinned commit. The Windows window is still ours.
source "$repo/scripts/tests/fork-window.sh"
fork_window_or_skip "$repo"
src="$repo/renderer/desktop/src"
fork_src="$fork_window/graalvm"
scripts="$repo/renderer/desktop/scripts"
failures=0

echo "desktop C symbols"

# The Host's own exports are left out. Those are the application's, resolved when it
# loads the renderer, and no C file here defines them or should.
declared="$(grep -rho '@CFunction("[a-z0-9_]*")' "$src" |
    sed 's/@CFunction("\(.*\)")/\1/' | grep -v '^compose_rust_host_' | sort -u)"
[ -n "$declared" ] || { echo "  FAIL: no @CFunction declarations found at all" >&2; exit 1; }

# What the fork's two wrappers declare, which only their own desktop's C has to answer in full:
# the wrappers carry calls (window actions, debug hooks) that the renderer's loops never make, so
# an image for another desktop never links them.
fork_names() {
    grep -rho '@CFunction("[a-z0-9_]*")' "$fork_src/$1" --include='*.kt' |
        sed 's/@CFunction("\(.*\)")/\1/' | sort -u
}
macos_fork_names="$(fork_names graalvm-macos)"
linux_fork_names="$(fork_names graalvm-linux)"
# The calls the renderer's own loops make through those wrappers, which every desktop's image
# reaches because the platform switch names all three loops.
loop_names="dxc_native_window_open dxc_native_window_size dxc_native_frame_begin
dxc_native_frame_end dxc_native_poll_event dxc_native_pump dxc_native_window_closed
dxc_native_set_cursor dxc_native_set_accessibility dxc_native_install_menu
dxc_native_set_frame_callback"
declared="$(printf '%s\n' $declared $loop_names | sort -u)"

# Which C files each desktop compiles, read from its own build script rather than listed
# again here, so that adding a file to a build is not a way of quietly failing this.
macos_files="$(grep -ho 'c/[a-z0-9_]*\.[mc]' "$scripts/build-native.sh" | sort -u)
$fork_window/graalvm/graalvm-macos/native/appkit_window.m"
linux_files="$(grep -ho 'c/[a-z0-9_]*\.[mc]' "$scripts/build-native-linux.sh" | sort -u)
$fork_window/graalvm/graalvm-linux/c/x11_window.c"
windows_files="$(grep -ho 'c[/\\][a-z0-9_]*\.[mc]' "$scripts/build-native-windows.ps1" |
    tr '\\' '/' | sort -u)"

# What one desktop's compiler sees of a file: the lines inside conditionals that hold on
# that desktop. Only the platform macros are known (`_WIN32`, `__APPLE__`, `__linux__`);
# a condition on anything else keeps both of its branches, which can only make this test
# report a duplicate it should not, never hide a missing definition. Without this a
# function written once per platform under `#ifdef _WIN32` / `#else` counted twice.
for_desktop() {
    local macro="$1" file="$2"
    awk -v want="$macro" '
        function known(name) { return name == "_WIN32" || name == "__APPLE__" || name == "__linux__" }
        # Each open conditional: 1 = its live branch is this one, 0 = not, 2 = unknown.
        function live(   i) { for (i = 1; i <= depth; i++) if (state[i] == 0) return 0; return 1 }
        function cond(text,   name, neg) {
            neg = 0
            if (text ~ /^!/) { neg = 1; sub(/^![ \t]*/, "", text) }
            if (text ~ /^defined[ \t]*\(/) { sub(/^defined[ \t]*\([ \t]*/, "", text); sub(/[ \t]*\).*$/, "", text) }
            else if (text ~ /^defined[ \t]+/) { sub(/^defined[ \t]+/, "", text); sub(/[ \t].*$/, "", text) }
            else return 2
            if (!known(text)) return 2
            return neg ? (text != want) : (text == want)
        }
        /^[ \t]*#[ \t]*ifdef[ \t]/  { n = $0; sub(/^[ \t]*#[ \t]*ifdef[ \t]+/, "", n); sub(/[ \t].*$/, "", n)
                                       depth++; state[depth] = known(n) ? (n == want) : 2; taken[depth] = state[depth]; next }
        /^[ \t]*#[ \t]*ifndef[ \t]/ { n = $0; sub(/^[ \t]*#[ \t]*ifndef[ \t]+/, "", n); sub(/[ \t].*$/, "", n)
                                       depth++; state[depth] = known(n) ? (n != want) : 2; taken[depth] = state[depth]; next }
        /^[ \t]*#[ \t]*if[ \t]/     { c = $0; sub(/^[ \t]*#[ \t]*if[ \t]+/, "", c)
                                       depth++; state[depth] = cond(c); taken[depth] = state[depth]; next }
        /^[ \t]*#[ \t]*elif[ \t]/   { c = $0; sub(/^[ \t]*#[ \t]*elif[ \t]+/, "", c)
                                       if (taken[depth] == 1) state[depth] = 0
                                       else { state[depth] = (taken[depth] == 2) ? 2 : cond(c); if (state[depth] != 0) taken[depth] = state[depth] }
                                       next }
        /^[ \t]*#[ \t]*else/         { state[depth] = (taken[depth] == 2) ? 2 : (taken[depth] == 1 ? 0 : 1); next }
        /^[ \t]*#[ \t]*endif/        { depth--; next }
        live() { print }
    ' "$file"
}

check() {
    local desktop="$1" macro="$2" own="$3"
    shift 3
    local files=""
    for relative in "$@"; do
        case "$relative" in
            /*) files="$files $relative" ;;
            *) files="$files $repo/renderer/desktop/$relative" ;;
        esac
    done
    [ -n "$files" ] || { echo "  FAIL: no C files read for $desktop" >&2; failures=$((failures + 1)); return; }
    local missing="" duplicated=""
    for symbol in $declared $own; do
        # A definition, not a mention: the name after a return type at the start of a
        # line, which is how every one of these files writes them.
        local defined
        # Ending in `|| true`, because a symbol nothing defines is what this is looking
        # for and grep answers that with a failure the shell would otherwise exit on.
        defined=0
        for file in $files; do
            [ -f "$file" ] || continue
            local count
            count="$(for_desktop "$macro" "$file" |
                grep -Ec "^[a-zA-Z_][a-zA-Z0-9_ *]*[ *]$symbol\(" || true)"
            defined=$((defined + count))
        done
        [ -n "$defined" ] || defined=0
        if [ "$defined" -eq 0 ]; then
            missing="$missing $symbol"
        elif [ "$defined" -gt 1 ]; then
            duplicated="$duplicated $symbol"
        fi
    done
    if [ -n "$missing" ] || [ -n "$duplicated" ]; then
        [ -n "$missing" ] && echo "  FAIL: $desktop defines nothing for:$missing" >&2
        [ -n "$duplicated" ] && echo "  FAIL: $desktop defines twice:$duplicated" >&2
        failures=$((failures + 1))
    else
        echo "  ok: $desktop answers every name once"
    fi
}

check macOS __APPLE__ "$macos_fork_names" $macos_files
check Linux __linux__ "$linux_fork_names" $linux_files
check Windows _WIN32 "" $windows_files

if [ "$failures" -ne 0 ]; then
    echo "  $failures of 3 desktops would not link" >&2
    exit 1
fi
echo "  ok: $(echo "$declared" | wc -l | tr -d ' ') symbols across 3 desktops"
