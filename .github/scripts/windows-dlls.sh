#!/usr/bin/env bash
# Sourced by check-single-executable.sh (and tested by scripts/tests/windows-dlls.test.sh).
# What a Windows executable may ask the loader for, as pure functions of names and text, so
# the rules are tested on any machine and not only on the runner that has dumpbin.

# windows_dll_verdict <dll name>
# Prints one word:
#   allowed    part of Windows and expected of this executable (the allowlist)
#   forbidden  something that has to be inside the executable, or must not exist at all: a
#              Visual C++ or MinGW runtime, the renderer, a Java runtime, an ICU library
#   unknown    anything else; the caller decides whether Windows ships it
windows_dll_verdict() {
    local lower
    lower="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
    case "$lower" in
        vcruntime*|msvcp*|concrt*|vccorlib*|libgcc*|libstdc++*|libwinpthread*|*mingw*|\
        libcompose_rust_renderer*|*jvm*|*java*|awt*|jawt*|skiko*|skia*|icu*|jni*)
            echo forbidden ;;
        kernel32.dll|user32.dll|gdi32.dll|ole32.dll|oleaut32.dll|imm32.dll|shell32.dll|\
        ntdll.dll|uiautomationcore.dll|d3d12.dll|dxgi.dll|d3dcompiler_47.dll|\
        api-ms-win-*|ext-ms-win-*)
            echo allowed ;;
        *) echo unknown ;;
    esac
}

# parse_dll_dependents
# Reads `dumpbin /dependents` (or llvm-readobj --coff-imports, or objdump -p) text on standard
# input and prints each DLL it names once, one per line.
parse_dll_dependents() {
    tr -d '\r' | sed -n \
        -e 's/^[[:space:]]*\([A-Za-z0-9_.+-]*\.[dD][lL][lL]\)[[:space:]]*$/\1/p' \
        -e 's/^[[:space:]]*Name: \([A-Za-z0-9_.+-]*\.[dD][lL][lL]\)[[:space:]]*$/\1/p' \
        -e 's/^[[:space:]]*DLL Name: \([A-Za-z0-9_.+-]*\.[dD][lL][lL]\)[[:space:]]*$/\1/p' | sort -fu
}
