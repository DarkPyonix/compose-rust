#!/usr/bin/env bash
# The Windows static renderer job keeps no Kotlin toolchain cache of its own. Every CI run is
# dispatched on a work branch, and a cache saved there is visible only to that branch and to
# no other; saved from develop it would still be invisible to work branches, since a branch
# reads only its own caches and the default branch's (main). The cache therefore never hit in
# any run, and its restore and save only cost time. This keeps it from coming back unnoticed.
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/windows-static.yml"
red=0
if grep -q 'kotlin-native-windows-' "$workflow"; then
    echo "fail: windows-static.yml caches the Kotlin toolchain again; that cache never hits on a dispatched run"
    red=1
fi
if grep -q 'toolchain-cache' "$workflow"; then
    echo "fail: windows-static.yml still refers to a toolchain-cache step"
    red=1
fi
(( red == 0 )) && echo "ok - windows-static.yml keeps no Kotlin toolchain cache"
exit $red
