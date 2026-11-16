#!/usr/bin/env bash
# Usage: git diff --name-only <base> <head> | needs-macos.sh
#
# Reads the changed file names on stdin and prints "macos=true" or "macos=false" for
# GITHUB_OUTPUT. false means every changed file is one that no macOS job can be affected
# by, so test.yml leaves its macOS jobs out. It fails open: an empty list, an unreadable
# list or any file not named below prints true, so a doubt always runs the macOS jobs.
#
# Never affecting macOS, by path:
#   docs/                         documentation and the planning documents
#   *.md at the root, LICENSE, .gitignore
#                                 text that nothing builds
#   .github/workflows/{pages,publish-crates,release-sync,windows-static,test-windows-c,
#   test-windows-resize-metrics}.yml
#                                 workflows no shell test reads. The others stay on the macOS
#                                 side: install-nik.test.sh, kn-start-report.test.sh and
#                                 others read them, and install-nik only means anything on
#                                 macOS.
#   renderer/windows/, renderer/staticlib-windows/, renderer/desktop/scripts/*.ps1,
#   renderer/desktop/scripts/build-windows.sh,
#   renderer/desktop/scripts/windows-build-contract.test.sh,
#   renderer/desktop/scripts/windows-metadata/
#                                 the Windows renderer's own sources and build
#   scripts/check-windows-consumer.sh
#                                 the Windows consumer check
#
# Everything else counts as macOS-relevant, including .github/scripts/ (the shell suite
# runs on macOS and tests them), samples/, compose-rust/ and the rest of renderer/.
set -uo pipefail

files="$(cat 2>/dev/null)" || { echo "macos=true"; exit 0; }
[[ -n "$files" ]] || { echo "macos=true"; exit 0; }

while IFS= read -r file; do
    [[ -n "$file" ]] || continue
    case "$file" in
        docs/*) ;;
        .github/workflows/pages.yml|.github/workflows/publish-crates.yml|.github/workflows/release-sync.yml) ;;
        .github/workflows/windows-static.yml|.github/workflows/test-windows-c.yml) ;;
        .github/workflows/test-windows-resize-metrics.yml) ;;
        */*) case "$file" in
                 renderer/windows/*|renderer/staticlib-windows/*|renderer/desktop/scripts/windows-metadata/*) ;;
                 renderer/desktop/scripts/*.ps1|renderer/desktop/scripts/build-windows.sh) ;;
                 renderer/desktop/scripts/windows-build-contract.test.sh) ;;
                 scripts/check-windows-consumer.sh) ;;
                 *) echo "macos=true"; exit 0 ;;
             esac ;;
        *.md|LICENSE|.gitignore) ;;
        *) echo "macos=true"; exit 0 ;;
    esac
done <<< "$files"
echo "macos=false"
