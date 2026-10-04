#!/usr/bin/env bash
# The X11 window used to carry its own table from a key symbol to the shared key number, in
# C, beside a second one in the Kotlin/Native window. They disagreed (the Kotlin/Native one had
# no letters, so control with C, V, X, Z or A arrived as an unknown key). There is one table
# now, in `renderer/desktop/src/X11Keys.kt`, and the decision from a key and its text to events,
# and the model of a composition, are `ImeComposition.kt`. What is checked here is that the C
# window hands over the key symbol, the state word, the text and the preedit edits untouched,
# and that both windows ask that code. The code itself is checked by `X11KeysTest` and
# `X11EventsTest`.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
c_file="$repo_root/renderer/desktop/c/x11_window.c"
graalvm_window="$repo_root/renderer/desktop/src/X11Window.kt"
native_input="$repo_root/renderer/linux/src/LinuxInput.kt"
status=0
fail() { echo "FAIL: $1"; status=1; }

[[ -f "$c_file" && -f "$graalvm_window" && -f "$native_input" ]] || { echo "FAIL: a window source is missing"; exit 1; }

grep -q 'record.key_code = (int32_t)symbol;' "$c_file" \
    || fail "x11_window.c no longer records the key symbol as the server named it"
grep -Eq 'static int32_t dxc_key_code|static int32_t dxc_modifiers' "$c_file" \
    && fail "x11_window.c carries a key or modifier table of its own again"
grep -q 'X11Events(' "$graalvm_window" \
    || fail "X11Window.kt does not turn the window's records into events with the shared code"
grep -q 'keyEventsFor(' "$repo_root/renderer/linux/src/LinuxWindow.kt" \
    || fail "the Kotlin/Native window does not decide a key's events with the shared rule"
grep -Eq 'dxc_preedit\[|dxc_preedit_length' "$c_file" \
    && fail "x11_window.c keeps a composition buffer of its own again"
grep -q 'x11KeyNumber(' "$native_input" \
    || fail "the Kotlin/Native window does not ask the shared X11 key table"
grep -q 'x11Modifiers(' "$native_input" \
    || fail "the Kotlin/Native window does not ask the shared X11 modifier table"

[[ $status -eq 0 ]] && echo "both X11 windows read one key table: ok"
exit $status
