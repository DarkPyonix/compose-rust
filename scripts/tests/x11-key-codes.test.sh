#!/usr/bin/env bash
# Both X11 windows read one key table and one input method model. The GraalVM X11 window used
# to carry its own table from a key symbol to the shared key number, and its own composition
# buffer, in C, beside the Kotlin/Native window's in Kotlin, and the two disagreed (the
# Kotlin/Native table had no letters, so control with C, V, X, Z or A arrived as an unknown key).
# The table is the fork's common X11Keys.kt and the model its ImeComposition.kt; the code itself
# is checked there, by X11KeysTest and X11EventsTest. What is checked here, at the commit the
# renderer pins, is that the C window hands over the key symbol, the state word, the text and
# the preedit edits untouched, and that both windows ask the shared code.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/tests/fork-window.sh"
fork_window_or_skip "$repo_root"
c_file="$fork_window/graalvm/graalvm-linux/c/x11_window.c"
graalvm_window="$fork_window/graalvm/graalvm-linux/src/org/thisisthepy/compose/window/graalvm/linux/X11Window.kt"
native_window="$fork_window/native/linux/src/X11Window.kt"
table="$fork_window/common/src/org/thisisthepy/compose/window/X11Keys.kt"
status=0
fail() { echo "FAIL: $1"; status=1; }

for file in "$c_file" "$graalvm_window" "$native_window" "$table"; do
    [[ -f "$file" ]] || { echo "FAIL: missing $file"; exit 1; }
done

grep -q 'record.key_code = (int32_t)symbol;' "$c_file" \
    || fail "x11_window.c no longer records the key symbol as the server named it"
grep -Eq 'static int32_t dxc_key_code|static int32_t dxc_modifiers' "$c_file" \
    && fail "x11_window.c carries a key or modifier table of its own again"
grep -Eq 'dxc_preedit\[|dxc_preedit_length' "$c_file" \
    && fail "x11_window.c keeps a composition buffer of its own again"
grep -q 'DXC_EVENT_PREEDIT_DRAW' "$c_file" \
    || fail "x11_window.c does not pass the input method's preedit edits on"
grep -q 'X11Events(' "$graalvm_window" \
    || fail "the GraalVM X11Window does not turn the window's records into events with the shared code"
grep -q 'keyEventsFor(' "$native_window" \
    || fail "the Kotlin/Native window does not decide a key's events with the shared rule"
grep -q 'fun x11KeyNumber(' "$table" || fail "the shared key table is gone from common"

# What the table answers, which is what the C table answered before it moved: the shortcut
# letters on the shared board (zero is the A key, so an unknown key must not be zero), a digit,
# the named keys, and -1 for a key with no number.
hex_list() { sed -n "/^private val $1 = intArrayOf(/,/)/p" "$table" | tr -d '\n' |
    sed -E 's/.*intArrayOf\(//; s/\).*//' | tr ',' '\n' | sed -E 's/ //g' | grep . ; }
letters=($(hex_list X11_LETTERS))
digits=($(hex_list X11_DIGITS))
[[ ${#letters[@]} -eq 26 ]] || fail "the shared letter table has ${#letters[@]} entries, not 26"
[[ ${#digits[@]} -eq 10 ]] || fail "the shared digit table has ${#digits[@]} entries, not 10"
letter() { printf '%d' "${letters[$(( $(printf '%d' "'$1") - 97 ))]}"; }
got="c $(letter c)
v $(letter v)
x $(letter x)
z $(letter z)
a $(letter a)
5 $(printf '%d' "${digits[5]}")"
expected="c 8
v 9
x 7
z 6
a 0
5 23"
[[ "$got" == "$expected" ]] || fail "the shared key table answers differently from the shared board: $(diff <(echo "$expected") <(echo "$got") | tr '\n' ' ')"
grep -Eq 'XK_RETURN, XK_KP_ENTER -> 0x24' "$table" || fail "Return is not key 36 in the shared table"
grep -Eq 'XK_INSERT -> 0x72' "$table" || fail "Insert is not key 114 in the shared table"
grep -Eq 'else -> NO_KEY' "$table" || fail "a key the table does not know does not answer NO_KEY"
grep -Eq 'const val NO_KEY = -1' "$table" || fail "NO_KEY is not -1, and zero is the A key"

[[ $status -eq 0 ]] && echo "both X11 windows read one key table and one input method model: ok"
exit $status
