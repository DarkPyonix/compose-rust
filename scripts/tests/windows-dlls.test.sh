#!/usr/bin/env bash
# The rules check-single-executable.sh applies to a Windows executable's imports: which DLLs
# are Windows' own, which must be inside the executable instead, and how the three tools that
# can list them are read.
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../../.github/scripts/windows-dlls.sh
source "$repo_root/.github/scripts/windows-dlls.sh"

failures=0
check() {
    if [[ "$2" == "$3" ]]; then echo "ok   - $1"; else echo "FAIL - $1: expected [$3], got [$2]"; failures=$((failures + 1)); fi
}

for dll in KERNEL32.dll USER32.dll GDI32.dll ole32.dll OLEAUT32.dll IMM32.dll SHELL32.dll \
           ntdll.dll UIAutomationCore.dll d3d12.dll dxgi.dll D3DCOMPILER_47.dll \
           api-ms-win-crt-runtime-l1-1-0.dll API-MS-WIN-CORE-synch-l1-2-0.dll; do
    check "$dll is allowed" "$(windows_dll_verdict "$dll")" allowed
done
for dll in VCRUNTIME140.dll VCRUNTIME140_1.dll MSVCP140.dll msvcp140_1.dll libgcc_s_seh-1.dll \
           libstdc++-6.dll libwinpthread-1.dll libcompose_rust_renderer.dll jvm.dll awt.dll \
           skiko-windows-x64.dll icuuc.dll jawt.dll; do
    check "$dll is forbidden" "$(windows_dll_verdict "$dll")" forbidden
done
check "advapi32.dll is not on the list" "$(windows_dll_verdict ADVAPI32.dll)" unknown

dumpbin_text=$'Dump of file consumer.exe\r\n\r\nFile Type: EXECUTABLE IMAGE\r\n\r\n  Image has the following dependencies:\r\n\r\n    KERNEL32.dll\r\n    USER32.dll\r\n    KERNEL32.dll\r\n\r\n  Summary\r\n\r\n        1000 .data\r\n'
check "dumpbin text" "$(parse_dll_dependents <<< "$dumpbin_text" | tr '\n' ' ')" "KERNEL32.dll USER32.dll "
check "llvm-readobj text" "$(printf 'Import {\n  Name: ole32.dll\n  ImportLookupTableRVA: 0x1\n}\nImport {\n  Name: dxgi.dll\n}\n' | parse_dll_dependents | tr '\n' ' ')" "dxgi.dll ole32.dll "
check "objdump text" "$(printf '\tDLL Name: gdi32.dll\n\tvma:  Hint/Ord Member-Name\n' | parse_dll_dependents | tr '\n' ' ')" "gdi32.dll "
check "no DLL in the text" "$(printf 'nothing here\n' | parse_dll_dependents)" ""

[[ "$failures" -eq 0 ]] || exit 1
echo "all passed"
