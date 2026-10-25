#!/usr/bin/env bash
# Stops with a clear message when the renderer's shared sources were checked out as text.
#
# Several renderer modules share one copy of the interpreter through symlinks. Git with
# core.symlinks=false (the Windows default without developer mode) checks each of them out as
# a one-line text file holding the target path, and the build then fails far from the cause.
# One known link stands for all of them.
#
# Usage: require-symlinks.sh [repo-root]
set -euo pipefail

root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
probe="renderer/ios/src/shared/WindowSize.kt"

if [[ -e "$root/$probe" && ! -L "$root/$probe" ]]; then
  cat >&2 <<MSG
error: $probe is a regular file, but it must be a symlink.
Git checked the repository's symlinks out as text files because core.symlinks is false.
Fix it, then check the files out again:
  git config core.symlinks true
  git checkout -f
On Windows, enable Developer Mode (or run git as administrator) so that git can create symlinks.
MSG
  exit 1
fi
