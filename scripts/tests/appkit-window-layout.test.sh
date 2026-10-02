#!/usr/bin/env bash
# The window the renderer opens for itself is four pointers and a fifth, and the Kotlin
# side reads them by offset. Nothing in either language checks the other, so a field added
# to the C struct would leave Kotlin reading a pointer from the wrong place: a window
# handle used as a Metal device, which is a crash with no line in it to read.
#
# This compiles the struct and asks C where each field is, then checks that the Kotlin
# side reads from there.

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_file="$repo_root/dioxus-compose-renderer/desktop/c/appkit_window.m"
kotlin_file="$repo_root/dioxus-compose-renderer/desktop/src/AppKitWindow.kt"
red=0

[[ -f "$source_file" ]] || { echo "missing $source_file"; exit 1; }
[[ -f "$kotlin_file" ]] || { echo "missing $kotlin_file"; exit 1; }

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "skipped: the window this describes is macOS's"
    exit 0
fi

probe="$(mktemp -t appkit-window-layout).m"
trap 'rm -f "$probe" "${probe%.m}"' EXIT
{
    echo '#include <stdio.h>'
    echo '#include <stddef.h>'
    # The declarations themselves, taken from the file under test rather than restated.
    # The size the text field is declared with comes from there as well, so the two cannot
    # be changed apart.
    grep '^#define DXC_TEXT_BYTES' "$source_file"
    sed -n '/^struct dxc_native_window {/,/^};/p' "$source_file"
    sed -n '/^struct dxc_event {/,/^};/p' "$source_file"
    sed -n '/^struct dxc_element {/,/^};/p' "$source_file"
    echo 'int main(void) {'
    echo '    printf("window %zu\nview %zu\ndevice %zu\nqueue %zu\nlayer %zu\nsize %zu\n",'
    echo '        offsetof(struct dxc_native_window, window),'
    echo '        offsetof(struct dxc_native_window, view),'
    echo '        offsetof(struct dxc_native_window, device),'
    echo '        offsetof(struct dxc_native_window, queue),'
    echo '        offsetof(struct dxc_native_window, layer),'
    echo '        sizeof(struct dxc_native_window));'
    echo '    printf("kind %zu\nx %zu\ny %zu\nbuttons %zu\nmodifiers %zu\nkey_code %zu\ncode_point %zu\ntext %zu\nevent_size %zu\n",'
    echo '        offsetof(struct dxc_event, kind),'
    echo '        offsetof(struct dxc_event, x),'
    echo '        offsetof(struct dxc_event, y),'
    echo '        offsetof(struct dxc_event, buttons),'
    echo '        offsetof(struct dxc_event, modifiers),'
    echo '        offsetof(struct dxc_event, key_code),'
    echo '        offsetof(struct dxc_event, code_point),'
    echo '        offsetof(struct dxc_event, text),'
    echo '        sizeof(struct dxc_event));'
    echo '    printf("element_label %zu\nelement_size %zu\n",'
    echo '        offsetof(struct dxc_element, label),'
    echo '        sizeof(struct dxc_element));'
    echo '    return 0;'
    echo '}'
} > "$probe"

cc -o "${probe%.m}" "$probe" || { echo "the struct did not compile"; exit 1; }
layout="$("${probe%.m}")"

check_field() {
    local field="$1"
    local offset
    offset="$(awk -v f="$field" '$1 == f { print $2 }' <<< "$layout")"
    if ! grep -q "$field = out.readWord<Pointer>($offset)" "$kotlin_file"; then
        echo "fail: C puts $field at $offset, which is not where AppKitWindow.kt reads it"
        red=1
    fi
}

for field in window view device queue layer; do
    check_field "$field"
done

check_event_field() {
    local field="$1" reader="$2" name="$3"
    local offset
    offset="$(awk -v f="$field" '$1 == f { print $2 }' <<< "$layout")"
    if ! grep -q "$name = record.$reader($offset)" "$kotlin_file"; then
        echo "fail: C puts $field at $offset, which is not where AppKitWindow.kt reads it"
        red=1
    fi
}

check_event_field kind readInt kind
check_event_field x readFloat x
check_event_field y readFloat y
check_event_field buttons readInt buttons
check_event_field modifiers readInt modifiers
check_event_field key_code readInt keyCode
check_event_field code_point readInt codePoint

text_offset="$(awk '$1 == "text" { print $2 }' <<< "$layout")"
if ! grep -q "TEXT_OFFSET = $text_offset" "$kotlin_file"; then
    echo "fail: C puts the text at $text_offset, which is not where AppKitWindow.kt reads it"
    red=1
fi

element_label="$(awk '$1 == "element_label" { print $2 }' <<< "$layout")"
if ! grep -q "ELEMENT_LABEL_OFFSET = $element_label" "$kotlin_file"; then
    echo "fail: C puts an element's label at $element_label, not where AppKitWindow.kt writes it"
    red=1
fi

element_size="$(awk '$1 == "element_size" { print $2 }' <<< "$layout")"
if ! grep -q "ELEMENT_BYTES = $element_size" "$kotlin_file"; then
    echo "fail: an element is $element_size bytes, which is not what AppKitWindow.kt writes"
    red=1
fi

event_size="$(awk '$1 == "event_size" { print $2 }' <<< "$layout")"
if ! grep -q "EVENT_STRUCT_BYTES = $event_size" "$kotlin_file"; then
    echo "fail: an event is $event_size bytes, which is not what AppKitWindow.kt reserves"
    red=1
fi

size="$(awk '$1 == "size" { print $2 }' <<< "$layout")"
if ! grep -q "WINDOW_STRUCT_BYTES = $size" "$kotlin_file"; then
    echo "fail: the struct is $size bytes, which is not what AppKitWindow.kt reserves"
    red=1
fi

if (( red )); then
    exit 1
fi
echo "ok: the window's fields are read from where C puts them"
