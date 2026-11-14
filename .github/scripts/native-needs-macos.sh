#!/usr/bin/env bash
# Usage: git diff --no-renames --name-only <base> <head> | native-needs-macos.sh
#
# Reads the changed file names on stdin and prints "macos=true" or "macos=false" for
# GITHUB_OUTPUT. test-native-renderer.yml builds the macOS static renderer, checks the macOS
# single executable and builds the iOS renderer on macOS runners; false means every changed
# file is one of the other platforms' own, so those jobs are left out. It fails open: an empty
# list, an unreadable list or any file not named below prints true.
#
# The other platforms' own files (they feed no macOS job of this workflow):
#   renderer/windows/, renderer/staticlib-windows/, renderer/linux/, renderer/staticlib-linux/
#   renderer/desktop/scripts/*.ps1, build-windows.sh, build-linux.sh,
#   renderer/desktop/scripts/windows-build-contract.test.sh, windows-metadata/
#   scripts/check-windows-consumer.sh and the multi-window fixture only it builds
#   (compose-rust/tests/fixtures/multi-window/), the HiDPI check on Windows with its scale tool
#   and the fixture only it builds here (.github/scripts/hidpi-windows-check.sh,
#   set-display-scale.c, compose-rust/tests/fixtures/hidpi/), .github/scripts/check-linux-input-access.sh,
#   .github/scripts/atspi-walk.py, .github/workflows/windows-static.yml
#
# Everything else counts as macOS-relevant: the shared renderer sources, the build scripts,
# package-static-renderer.sh and check-single-executable.sh (the macOS legs run them),
# compose-rust/build*, parity scripts and the workflows themselves.
set -uo pipefail

files="$(cat 2>/dev/null)" || { echo "macos=true"; exit 0; }
[[ -n "$files" ]] || { echo "macos=true"; exit 0; }

while IFS= read -r file; do
    [[ -n "$file" ]] || continue
    case "$file" in
        renderer/windows/*|renderer/staticlib-windows/*|renderer/linux/*|renderer/staticlib-linux/*) ;;
        renderer/desktop/scripts/windows-metadata/*|renderer/desktop/scripts/*.ps1) ;;
        renderer/desktop/scripts/build-windows.sh|renderer/desktop/scripts/build-linux.sh) ;;
        renderer/desktop/scripts/windows-build-contract.test.sh) ;;
        scripts/check-windows-consumer.sh|.github/scripts/check-linux-input-access.sh) ;;
        compose-rust/tests/fixtures/multi-window/*|compose-rust/tests/fixtures/hidpi/*) ;;
        .github/scripts/hidpi-windows-check.sh|.github/scripts/set-display-scale.c) ;;
        .github/scripts/atspi-walk.py|.github/workflows/windows-static.yml) ;;
        *) echo "macos=true"; exit 0 ;;
    esac
done <<< "$files"
echo "macos=false"
