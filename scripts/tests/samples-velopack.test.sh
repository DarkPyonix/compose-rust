#!/usr/bin/env bash
# Usage: ./scripts/tests/samples-velopack.test.sh
#
# Every desktop sample carries Velopack's SDK and answers its hooks before anything else.
#
# The sample releases install and update through Velopack (Setup.exe, .msi, the .dmg and
# the AppImage), and Velopack's installer and updater start the program with hook
# arguments (--veloapp-install, --veloapp-updated, --veloapp-obsolete,
# --veloapp-uninstall) and wait for it to answer and exit. A program that does not answer
# opens its window in the middle of an install instead. So:
#
# - each sample's main calls velopack::VelopackApp::build().run() as its first statement,
#   behind the desktop cfg;
# - velopack is a dependency of the samples on the desktop only, never on Android, iOS or
#   the web, and never of the compose-rust crate itself;
# - its version is the vpk version the packaging workflows install, because Velopack asks
#   for the SDK and vpk to match;
# - the samples workflow runs every built sample with each hook and requires it to exit
#   at once (.github/scripts/check-sample-velopack-hooks.py).
#
# This reads files only, so it runs everywhere; the hooks themselves are run by the
# samples workflow on each desktop row.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

failures=0
pass() { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; failures=$((failures + 1)); }

desktop_cfg='cfg(not(any(target_os = "android", target_os = "ios", target_family = "wasm")))'

checked=0
for main in samples/*/src/main.rs; do
    sample="$(basename "$(dirname "$(dirname "$main")")")"
    checked=$((checked + 1))
    # The first two lines of code after `fn main() {`, without leading space or comments.
    body="$(awk '/^fn main\(\) \{/{found=1; next} found && NF && $1 !~ /^\/\// {print; n++} n == 2 {exit}' "$main" | sed 's/^ *//')"
    expected="#[$desktop_cfg]
velopack::VelopackApp::build().run();"
    if [[ "$body" == "$expected" ]]; then
        pass "fr22_6_7_$sample answers Velopack's hooks first"
    else
        fail "fr22_6_7_$sample answers Velopack's hooks first: main starts with
$(sed 's/^/       /' <<<"$body")"
    fi
    manifest="samples/$sample/Cargo.toml"
    # velopack.workspace = true under the desktop target table, and nowhere else.
    section="$(awk -v want="[target.'$desktop_cfg'.dependencies]" '
        /^\[/ {current = $0}
        /^velopack/ {print current}' "$manifest")"
    if [[ "$section" == "[target.'$desktop_cfg'.dependencies]" ]] \
        && grep -qx 'velopack.workspace = true' "$manifest"; then
        pass "fr22_6_7_$sample depends on velopack on the desktop only"
    else
        fail "fr22_6_7_$sample depends on velopack on the desktop only: velopack is under '${section:-nothing}'"
    fi
done
[[ "$checked" -gt 0 ]] || fail "fr22_6_7_found_samples: no samples/*/src/main.rs"

pin="$(sed -n 's/^velopack = "=\(.*\)"$/\1/p' Cargo.toml)"
vpk="$(sed -n "s/^ *VPK_VERSION: '\(.*\)'$/\1/p" .github/workflows/packager-macos.yml | sort -u)"
if [[ -n "$pin" && "$pin" == "$vpk" ]]; then
    pass "fr22_6_7_the_samples_sdk_is_the_vpk_version ($pin)"
else
    fail "fr22_6_7_the_samples_sdk_is_the_vpk_version: workspace velopack is '${pin:-unset}', vpk is '$vpk'"
fi

if grep -q velopack compose-rust/Cargo.toml; then
    fail "fr22_6_7_the_crate_does_not_depend_on_velopack: compose-rust/Cargo.toml names it"
else
    pass "fr22_6_7_the_crate_does_not_depend_on_velopack"
fi

if grep -q 'check-sample-velopack-hooks.py' .github/workflows/test-samples.yml; then
    pass "fr22_6_7_the_samples_workflow_runs_the_hooks"
else
    fail "fr22_6_7_the_samples_workflow_runs_the_hooks: test-samples.yml does not call check-sample-velopack-hooks.py"
fi

[[ "$failures" -eq 0 ]] || { echo "$failures case(s) failed"; exit 1; }
echo "all passed"
