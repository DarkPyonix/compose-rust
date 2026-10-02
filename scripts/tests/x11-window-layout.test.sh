#!/usr/bin/env bash
# Compare all three event records everywhere. On Linux, also ask C for the actual
# offsets and sizes that the Kotlin reader uses.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_file="$repo_root/dioxus-compose-renderer/desktop/c/x11_window.c"
appkit_source="$repo_root/dioxus-compose-renderer/desktop/c/appkit_window.m"
win32_source="$repo_root/dioxus-compose-renderer/desktop/c/win32_window.c"
kotlin_file="$repo_root/dioxus-compose-renderer/desktop/src/X11Window.kt"
event_reader="$repo_root/dioxus-compose-renderer/desktop/src/AppKitWindow.kt"
for file in "$source_file" "$appkit_source" "$win32_source" "$kotlin_file" "$event_reader"; do
    [[ -f "$file" ]] || { echo "missing $file"; exit 1; }
done

declaration() {
    sed -n '/^struct dxc_event {/,/^};/p' "$1" | sed 's://.*::' | tr -s ' \t\n' ' '
}
element_declaration() {
    sed -n '/^struct dxc_element {/,/^};/p' "$1" | sed 's://.*::' | tr -s ' \t\n' ' '
}
x11_event="$(declaration "$source_file")"
appkit_event="$(declaration "$appkit_source")"
win32_event="$(declaration "$win32_source")"
if [[ -z "$x11_event" || "$x11_event" != "$appkit_event" || "$x11_event" != "$win32_event" ]]; then
    echo "fail: the three windows declare different dxc_event fields or order"
    exit 1
fi
# What the window tells a reader who cannot see it. The renderer writes these records once
# and every platform reads that one layout, so a field that moved in one file only would
# have one window reading a label where another reads a rectangle.
x11_element="$(element_declaration "$source_file")"
appkit_element="$(element_declaration "$appkit_source")"
if [[ -z "$x11_element" || "$x11_element" != "$appkit_element" ]]; then
    echo "fail: the X11 and macOS windows declare different dxc_element fields or order"
    exit 1
fi

text_bytes="$(grep '^#define DXC_TEXT_BYTES ' "$source_file")"
if [[ "$text_bytes" != "$(grep '^#define DXC_TEXT_BYTES ' "$appkit_source")" ||
      "$text_bytes" != "$(grep '^#define DXC_TEXT_BYTES ' "$win32_source")" ]]; then
    echo "fail: the three event text fields have different capacities"
    exit 1
fi

if [[ "$(uname -s)" != "Linux" ]]; then
    echo "ok: the three event declarations match; skipped Linux offsetof check"
    exit 0
fi

command -v cc >/dev/null 2>&1 || { echo "skipped: no C compiler for offsetof"; exit 0; }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
probe="$work/x11-window-layout.c"
{
    echo '#include <stdio.h>'
    echo '#include <stddef.h>'
    echo '#include <stdint.h>'
    grep '^#define DXC_TEXT_BYTES' "$source_file"
    sed -n '/^struct dxc_native_window {/,/^};/p' "$source_file"
    sed -n '/^struct dxc_event {/,/^};/p' "$source_file"
    sed -n '/^struct dxc_element {/,/^};/p' "$source_file"
    echo 'int main(void) {'
    for field in window view device queue layer; do
        echo "printf(\"$field %zu\\n\", offsetof(struct dxc_native_window, $field));"
    done
    echo 'printf("window_size %zu\n", sizeof(struct dxc_native_window));'
    for field in kind x y buttons modifiers key_code code_point text; do
        echo "printf(\"$field %zu\\n\", offsetof(struct dxc_event, $field));"
    done
    echo 'printf("event_size %zu\n", sizeof(struct dxc_event));'
    echo 'printf("element_label %zu\n", offsetof(struct dxc_element, label));'
    echo 'printf("element_size %zu\n", sizeof(struct dxc_element));'
    echo 'return 0; }'
} > "$probe"
cc -o "$work/layout" "$probe" || { echo "fail: the declarations did not compile"; exit 1; }
layout="$("$work/layout")"
offset() { awk -v f="$1" '$1 == f {print $2}' <<< "$layout"; }
red=0
for field in window view device queue layer; do
    value="$(offset "$field")"
    if ! grep -Fq "$field = out.readWord<Pointer>($value)" "$kotlin_file"; then
        echo "fail: X11Window.kt reads $field at the wrong offset"
        red=1
    fi
done
for entry in kind:readInt:kind x:readFloat:x y:readFloat:y buttons:readInt:buttons modifiers:readInt:modifiers key_code:readInt:keyCode code_point:readInt:codePoint; do
    IFS=: read -r field reader name <<< "$entry"
    value="$(offset "$field")"
    if ! grep -Fq "$name = record.$reader($value)" "$event_reader"; then
        echo "fail: the shared Kotlin reader reads $field at the wrong offset"
        red=1
    fi
done
grep -Fq "TEXT_OFFSET = $(offset text)" "$event_reader" || { echo "fail: text offset"; red=1; }
grep -Fq "EVENT_STRUCT_BYTES = $(offset event_size)" "$event_reader" || { echo "fail: event size"; red=1; }
grep -Fq "WINDOW_STRUCT_BYTES = $(offset window_size)" "$kotlin_file" || { echo "fail: window size"; red=1; }
# The accessibility records are written by the shared code the X11 window hands its tree to,
# so the offsets it writes at are checked against the C the X11 window declares.
grep -Fq "ELEMENT_LABEL_OFFSET = $(offset element_label)" "$event_reader" ||
    { echo "fail: an element's label is not written where C keeps it"; red=1; }
grep -Fq "ELEMENT_BYTES = $(offset element_size)" "$event_reader" ||
    { echo "fail: an element is not the size C declares"; red=1; }
(( red == 0 )) || exit 1
echo "ok: X11 fields and shared events match their Kotlin offsets"
