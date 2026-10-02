#!/usr/bin/env bash
# The window the renderer opens for itself on Windows is five pointers, and the Kotlin
# side reads them by offset. Nothing in either language checks the other, so a field added
# to the C struct would leave Kotlin reading a pointer from the wrong place: a window
# handle used as a Direct3D device, which is a crash with no line in it to read.
#
# The event struct has a second job. One piece of Kotlin reads what either platform's
# window recorded, so the two C declarations have to stay the same as each other; a field
# added to one of them alone is a scroll delta read as a button state.
#
# Two checks, then. The first compares the two declarations and needs nothing but the
# files, so it runs wherever this is run. The second compiles the struct and asks C where
# each field is, then checks that the Kotlin side reads from there.
#
# That second check prefers MSVC, because Windows is the machine this describes. It will
# take any C compiler though, and on a machine with no Windows at all that is the point:
# every field here is an `int32_t`, a `float` or a pointer, whose sizes and alignments are
# the same on every 64-bit target this project builds for, so a compiler that has never
# seen a Windows header still answers correctly. With no compiler at all, it skips.

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_file="$repo_root/dioxus-compose-renderer/desktop/c/win32_window.c"
kotlin_file="$repo_root/dioxus-compose-renderer/desktop/src/Win32Window.kt"
appkit_source="$repo_root/dioxus-compose-renderer/desktop/c/appkit_window.m"
appkit_kotlin="$repo_root/dioxus-compose-renderer/desktop/src/AppKitWindow.kt"
red=0

for file in "$source_file" "$kotlin_file" "$appkit_source" "$appkit_kotlin"; do
    [[ -f "$file" ]] || { echo "missing $file"; exit 1; }
done

# The declaration with its comments and its spacing taken out, so that two files may
# explain the same fields differently and still be compared on what they declare.
declaration() {
    sed -n "/^struct $2 {/,/^};/p" "$1" | sed 's://.*::' | tr -s ' \t\n' ' '
}

windows_event="$(declaration "$source_file" dxc_event)"
macos_event="$(declaration "$appkit_source" dxc_event)"
if [[ -z "$windows_event" ]]; then
    echo "fail: no struct dxc_event in $source_file"
    red=1
elif [[ "$windows_event" != "$macos_event" ]]; then
    echo "fail: the two windows record an event differently, and one Kotlin reader reads both"
    echo "  win32_window.c:  $windows_event"
    echo "  appkit_window.m: $macos_event"
    red=1
fi

if (( red )); then
    exit 1
fi

compiler=""
if command -v cl.exe >/dev/null 2>&1; then
    compiler="cl"
elif command -v cc >/dev/null 2>&1; then
    compiler="cc"
fi
if [[ -z "$compiler" ]]; then
    echo "skipped: no C compiler to ask where the fields are"
    exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
probe="$work/win32-window-layout.c"
{
    echo '#include <stdio.h>'
    echo '#include <stddef.h>'
    echo '#include <stdint.h>'
    # The declarations themselves, taken from the file under test rather than restated.
    # The size the text field is declared with comes from the file as well, so the two
    # cannot be changed apart.
    grep '^#define DXC_TEXT_BYTES' "$source_file"
    sed -n '/^struct dxc_native_window {/,/^};/p' "$source_file"
    sed -n '/^struct dxc_event {/,/^};/p' "$source_file"
    echo 'int main(void) {'
    echo '    printf("window %zu\ndevice %zu\nqueue %zu\nadapter %zu\nswapchain %zu\nsize %zu\n",'
    echo '        offsetof(struct dxc_native_window, window),'
    echo '        offsetof(struct dxc_native_window, device),'
    echo '        offsetof(struct dxc_native_window, queue),'
    echo '        offsetof(struct dxc_native_window, adapter),'
    echo '        offsetof(struct dxc_native_window, swapchain),'
    echo '        sizeof(struct dxc_native_window));'
    echo '    printf("kind %zu\nx %zu\ny %zu\nbuttons %zu\nmodifiers %zu\nkey_code %zu\ncode_point %zu\nevent_size %zu\n",'
    echo '        offsetof(struct dxc_event, kind),'
    echo '        offsetof(struct dxc_event, x),'
    echo '        offsetof(struct dxc_event, y),'
    echo '        offsetof(struct dxc_event, buttons),'
    echo '        offsetof(struct dxc_event, modifiers),'
    echo '        offsetof(struct dxc_event, key_code),'
    echo '        offsetof(struct dxc_event, code_point),'
    echo '        sizeof(struct dxc_event));'
    echo '    return 0;'
    echo '}'
} > "$probe"

if [[ "$compiler" == "cl" ]]; then
    ( cd "$work" && cl.exe /nologo /Fe:layout.exe win32-window-layout.c >/dev/null ) \
        || { echo "the struct did not compile"; exit 1; }
    binary="$work/layout.exe"
else
    cc -o "$work/layout" "$probe" || { echo "the struct did not compile"; exit 1; }
    binary="$work/layout"
fi
layout="$("$binary")"

check_field() {
    local field="$1"
    local offset
    offset="$(awk -v f="$field" '$1 == f { print $2 }' <<< "$layout")"
    if ! grep -q "$field = out.readWord<Pointer>($offset)" "$kotlin_file"; then
        echo "fail: C puts $field at $offset, which is not where Win32Window.kt reads it"
        red=1
    fi
}

for field in window device queue adapter swapchain; do
    check_field "$field"
done

# The event is read in AppKitWindow.kt, which is where `drainWindowEvents` lives and where
# both windows' events are turned into records. Checked from here as well as from the
# macOS test, because that test steps aside on a machine that is not a Mac and this is the
# one that runs where the Windows renderer is built.
check_event_field() {
    local field="$1" reader="$2" name="$3"
    local offset
    offset="$(awk -v f="$field" '$1 == f { print $2 }' <<< "$layout")"
    if ! grep -q "$name = record.$reader($offset)" "$appkit_kotlin"; then
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

event_size="$(awk '$1 == "event_size" { print $2 }' <<< "$layout")"
if ! grep -q "EVENT_STRUCT_BYTES = $event_size" "$appkit_kotlin"; then
    echo "fail: an event is $event_size bytes, which is not what AppKitWindow.kt reserves"
    red=1
fi

size="$(awk '$1 == "size" { print $2 }' <<< "$layout")"
if ! grep -q "WINDOW_STRUCT_BYTES = $size" "$kotlin_file"; then
    echo "fail: the struct is $size bytes, which is not what Win32Window.kt reserves"
    red=1
fi

if (( red )); then
    exit 1
fi
echo "ok: the window's fields are read from where C puts them"
