#!/usr/bin/env bash
# The X11 window turns a key symbol into the shared key number, and a key it does not know
# must answer -1. Zero is the A key on the board the numbers come from, so a default of zero
# made control with C, V or X arrive as control with A: copy, paste and cut all selected
# everything. This compiles the function out of x11_window.c against the key symbol values
# from the X11 headers (written down here, so no X11 install is needed) and checks it.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_file="$repo_root/renderer/desktop/c/x11_window.c"
[[ -f "$source_file" ]] || { echo "missing $source_file"; exit 1; }

dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
{
    cat <<'C'
#include <stdio.h>
#include <stdint.h>
typedef unsigned long KeySym;
#define XK_a 0x61
#define XK_z 0x7a
#define XK_A 0x41
#define XK_Z 0x5a
#define XK_0 0x30
#define XK_9 0x39
#define XK_Return 0xff0d
#define XK_KP_Enter 0xff8d
#define XK_Tab 0xff09
#define XK_ISO_Left_Tab 0xfe20
#define XK_space 0x20
#define XK_BackSpace 0xff08
#define XK_Escape 0xff1b
#define XK_Delete 0xffff
#define XK_Insert 0xff63
#define XK_Left 0xff51
#define XK_Right 0xff53
#define XK_Down 0xff54
#define XK_Up 0xff52
#define XK_Home 0xff50
#define XK_End 0xff57
#define XK_Prior 0xff55
#define XK_Next 0xff56
C
    sed -n '/^static int32_t dxc_key_code(KeySym key) {/,/^}/p' "$source_file"
    cat <<'C'
int main(void) {
    printf("c %d\nC %d\nv %d\nx %d\nz %d\na %d\n5 %d\nReturn %d\nunknown %d\nF1 %d\nInsert %d\n",
        dxc_key_code('c'), dxc_key_code('C'), dxc_key_code('v'), dxc_key_code('x'),
        dxc_key_code('z'), dxc_key_code('a'), dxc_key_code('5'), dxc_key_code(0xff0d),
        dxc_key_code(0xfd00), dxc_key_code(0xffbe), dxc_key_code(0xff63));
    return 0;
}
C
} > "$dir/probe.c"
cc -o "$dir/probe" "$dir/probe.c" || { echo "FAIL: dxc_key_code did not compile on its own"; exit 1; }
got="$("$dir/probe")"
expected="c 8
C 8
v 9
x 7
z 6
a 0
5 23
Return 36
unknown -1
F1 -1
Insert 114"
if [[ "$got" != "$expected" ]]; then
    echo "FAIL: the X11 key table answers differently from the shared board"
    diff <(echo "$expected") <(echo "$got")
    exit 1
fi
echo "the X11 key table maps shortcuts and answers -1 for unknown keys: ok"
