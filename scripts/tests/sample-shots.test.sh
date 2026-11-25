#!/usr/bin/env bash
# Usage: ./scripts/tests/sample-shots.test.sh
#
# Checks that scripts/sample-shots.sh stays aligned with the design systems recorded by
# samples/frames/src/lib.rs and invokes both halves of the recording pipeline.

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/scripts/sample-shots.sh"
frames="$repo_root/samples/frames/src/lib.rs"

failures=0
ok() { echo "ok   - $1"; }
fail() { echo "FAIL - $1" >&2; failures=$((failures + 1)); }

fr22_3_sample_shots_matches_all_design_systems() {
    local count
    count="$(sed -n 's/^pub const SYSTEMS: \[DesignSystem; \([0-9][0-9]*\)\].*/\1/p' "$frames" | head -1)"
    if [[ "$count" != "7" ]]; then
        fail "expected 7 design systems in samples/frames/src/lib.rs, found '${count:-none}'"
        return
    fi
    ok "samples/frames/src/lib.rs declares all 7 design systems"

    local system
    for system in Material3 Cupertino Fluent Gnome Breeze Deepin LiquidGlass; do
        if grep -qF "$system" "$script"; then
            ok "sample-shots.sh names $system"
        else
            fail "sample-shots.sh does not name design system $system"
        fi
    done

    if grep -qE 'all six|other five|all 144' "$script"; then
        fail "sample-shots.sh still carries stale 6-system / 144-shot wording"
    else
        ok "sample-shots.sh carries no stale 6-system or 144-shot wording"
    fi

    if grep -qF 'cargo test --workspace is_recorded' "$script" &&
       grep -qF 'SampleScreenshotTest' "$script"; then
        ok "sample-shots.sh runs both is_recorded and SampleScreenshotTest"
    else
        fail "sample-shots.sh does not run both recording and screenshot steps"
    fi
}

fr22_3_sample_shots_matches_all_design_systems

if [[ "$failures" -gt 0 ]]; then
    echo "$failures check(s) failed" >&2
    exit 1
fi
echo "all passed"
