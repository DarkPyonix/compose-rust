#!/usr/bin/env bash
# The renderer build scripts stop at the start, naming core.symlinks, when the shared sources
# were checked out as one-line text files (git with core.symlinks=false, the Windows default
# without developer mode). Without the check the build fails far from the cause.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
check="$repo_root/renderer/scripts/require-symlinks.sh"
probe_rel="renderer/ios/src/shared/WindowSize.kt"

red=0
fail() { echo "FAIL  $1"; red=1; }
pass() { echo "ok    $1"; }

[[ -f "$check" ]] || { fail "renderer/scripts/require-symlinks.sh exists"; exit 1; }

scratch="$repo_root/.scratch/renderer-symlinks-test.$$"
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/good/$(dirname "$probe_rel")" "$scratch/bad/$(dirname "$probe_rel")"
ln -s ../../../desktop/src/renderer/WindowSize.kt "$scratch/good/$probe_rel"
printf '../../../desktop/src/renderer/WindowSize.kt' > "$scratch/bad/$probe_rel"

if bash "$check" "$scratch/good" >/dev/null 2>&1; then pass "a real symlink passes"; else fail "a real symlink passes"; fi

out="$(bash "$check" "$scratch/bad" 2>&1)"; code=$?
if [[ $code -ne 0 ]]; then pass "a text file in place of the symlink stops the build"; else fail "a text file in place of the symlink stops the build"; fi
if grep -q "core.symlinks" <<<"$out" && grep -q "git checkout -f" <<<"$out"; then
  pass "the message names core.symlinks and the fix"
else
  fail "the message names core.symlinks and the fix"
fi

# The real checkout must pass its own check, and every renderer build script must call it.
if bash "$check" "$repo_root" >/dev/null 2>&1; then pass "this checkout has its symlinks"; else fail "this checkout has its symlinks"; fi
for s in build-windows.sh build-macos.sh build-linux.sh build-ios.sh build-native.sh build-native-linux.sh; do
  if grep -q "require-symlinks.sh" "$repo_root/renderer/desktop/scripts/$s"; then pass "$s runs the check"; else fail "$s runs the check"; fi
done
if grep -q "ReparsePoint" "$repo_root/renderer/desktop/scripts/build-native-windows.ps1" \
  && grep -q "core.symlinks" "$repo_root/renderer/desktop/scripts/build-native-windows.ps1"; then
  pass "build-native-windows.ps1 runs the check"
else
  fail "build-native-windows.ps1 runs the check"
fi
exit $red
