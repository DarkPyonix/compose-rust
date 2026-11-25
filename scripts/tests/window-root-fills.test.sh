#!/usr/bin/env bash
# Every real window draws the Host's tree through WindowContent, which fills the window.
#
# ComposeRustContent measures what it is given and reports that as the window's size. A
# platform window that calls it with no filling modifier reports the size of its content
# instead, so a window holding one line of text tells the Host it is one line tall.
# WindowContent fills first; this fails if a window entry point bypasses it.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
status=0

fail() { echo "FAIL: $1"; status=1; }

# The window entry points: each native platform's own sources (not the shared symlinks into
# the interpreter) and the desktop window files beside the interpreter.
files=()
for dir in macos linux windows; do
    while IFS= read -r f; do files+=("$f"); done < <(find "$repo_root/renderer/$dir/src" -name '*.kt' -not -path '*/shared/*' -type f)
done
while IFS= read -r f; do files+=("$f"); done < <(find "$repo_root/renderer/desktop/src" -maxdepth 1 -name '*.kt' -type f)

[[ ${#files[@]} -gt 0 ]] || { echo "FAIL: no window entry points found"; exit 1; }

for f in "${files[@]}"; do
    # A call, not a mention in a comment.
    if grep -nE '^[^/]*\bComposeRustContent\(' "$f" | grep -v '^\s*\*' >/dev/null; then
        fail "${f#"$repo_root"/} calls ComposeRustContent directly; a window must use WindowContent so it fills"
    fi
done

helper="$repo_root/renderer/desktop/src/renderer/ComposeRustHost.kt"
if ! awk '/^fun WindowContent\(/{on=1} on{print} on && /^}/{exit}' "$helper" | grep -q 'Modifier.fillMaxSize()'; then
    fail "WindowContent no longer fills the window"
fi

for entry in macos/src/MacosRenderer.kt macos/src/MacosMultiWindow.kt linux/src/LinuxRenderer.kt \
    windows/src/WindowsRenderer.kt windows/src/WindowsMultiWindow.kt \
    desktop/src/NativeWindowShell.kt desktop/src/SingleWindowBackend.kt; do
    grep -q 'WindowContent(' "$repo_root/renderer/$entry" || fail "renderer/$entry no longer draws through WindowContent"
done

# The GraalVM Windows window draws through SingleWindowHostContent, which is WindowContent
# for the one window, or for the active window of several (checked above in its own file).
grep -qE '(SingleWindowHostContent|[^A-Za-z]WindowContent)\(' "$repo_root/renderer/desktop/src/Win32Window.kt" ||
    fail "renderer/desktop/src/Win32Window.kt no longer draws through WindowContent"

[[ $status -eq 0 ]] && echo "every window's root fills the window: ok"
exit $status
