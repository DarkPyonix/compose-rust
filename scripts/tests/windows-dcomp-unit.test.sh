#!/usr/bin/env bash
# The Windows window's DirectComposition calls are a C++ file of their own, and every build that
# compiles the window compiles it too.
#
# Since the fork's PR #253, win32_window.c calls dxc_dcomp_* in c/win32_dcomp.cpp. dcomp.h
# declares overloaded COM methods and has no C-mode vtables, so that one file is C++, built
# without exceptions or RTTI (`/std:c++17 /GR- /EHs-c-`), and its object goes beside the C ones.
# A build that compiles win32_window.c and leaves it out compiles fine and stops at the link
# with an unresolved dxc_dcomp_create, which on Windows is found only by a Windows machine.
#
# This reads the fork's C at the pinned commit and every place here that compiles it: the
# Kotlin/Native kit (build-windows.sh, whose library the Host links whole), the native image
# (build-native-windows.ps1) and the pull request compile check (test-windows-c.yml). Nothing
# here needs Windows.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/tests/fork-window.sh"
fork_window_or_skip "$repo_root"
c_dir="$fork_window/windows-shared/c"
kn_build="$repo_root/renderer/desktop/scripts/build-windows.sh"
graal_build="$repo_root/renderer/desktop/scripts/build-native-windows.ps1"
compile_check="$repo_root/.github/workflows/test-windows-c.yml"
red=0

ok() { echo "ok    $1"; }
fail() { echo "FAIL  $1: $2"; red=1; }

for file in "$kn_build" "$graal_build" "$compile_check"; do
    [[ -f "$file" ]] || { echo "missing $file"; exit 1; }
done

# The pin is at or past the fork's DirectComposition window: the C++ unit is there and the
# window calls it.
name=fr19_8_4_window_pin_holds_the_dcomp_unit
if [[ ! -f "$c_dir/win32_dcomp.cpp" || ! -f "$c_dir/win32_dcomp.h" ]]; then
    fail "$name" "the pinned fork commit has no windows-shared/c/win32_dcomp.cpp and win32_dcomp.h"
elif ! grep -q '#include "win32_dcomp.h"' "$c_dir/win32_window.c"; then
    fail "$name" "the pinned win32_window.c does not call the DirectComposition unit"
else
    ok "$name"
fi

# The Kotlin/Native kit: compiled as C++ with the fork's flags, and a member of the one library
# the Host links whole.
name=fr19_8_4_kn_kit_compiles_and_archives_the_dcomp_unit
line="$(grep -E '^compile "\$c_dir/win32_dcomp\.cpp"' "$kn_build" || true)"
if [[ -z "$line" ]]; then
    fail "$name" "build-windows.sh does not compile \$c_dir/win32_dcomp.cpp"
elif ! grep -qF '/std:c++17' <<<"$line" || ! grep -qF '/GR-' <<<"$line" || ! grep -qF '/EHs-c-' <<<"$line"; then
    fail "$name" "build-windows.sh compiles win32_dcomp.cpp without /std:c++17 /GR- /EHs-c-: $line"
elif ! grep -qE 'win32_dcomp\.obj' <<<"$(awk '/rcs dxc-windows-native\.lib/ { on = 1 } on { print } on && !/\\$/ { exit }' "$kn_build")"; then
    fail "$name" "build-windows.sh leaves win32_dcomp.obj out of dxc-windows-native.lib"
else
    ok "$name"
fi

# The native image: compiled by cl as C++ with the same flags, and handed to the image's link.
name=fr19_8_4_native_image_compiles_and_links_the_dcomp_unit
if ! grep -qF '$DcompSource = Join-Path $WindowCDir "win32_dcomp.cpp"' "$graal_build"; then
    fail "$name" "build-native-windows.ps1 does not name the fork's win32_dcomp.cpp"
elif ! grep -qE 'cl\.exe .*/std:c\+\+17 /GR- /EHs-c- .*\$DcompSource' "$graal_build"; then
    fail "$name" "build-native-windows.ps1 does not compile win32_dcomp.cpp with cl /std:c++17 /GR- /EHs-c-"
elif ! grep -qF '"-H:NativeLinkerOption=$DcompObject"' "$graal_build"; then
    fail "$name" "build-native-windows.ps1 does not pass win32_dcomp.obj to the image's link"
else
    ok "$name"
fi

# The pull request compile check compiles what the builds compile, the C++ unit included.
name=fr19_8_4_windows_c_check_compiles_the_dcomp_unit
if ! grep -qF '"win32_dcomp.cpp"' "$compile_check"; then
    fail "$name" "test-windows-c.yml does not compile win32_dcomp.cpp"
elif ! grep -qE 'cl\.exe .*/std:c\+\+17 /GR- /EHs-c-' "$compile_check"; then
    fail "$name" "test-windows-c.yml does not compile the C++ unit with /std:c++17 /GR- /EHs-c-"
else
    ok "$name"
fi

exit $red
