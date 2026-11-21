#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

input_script="$repo_root/.github/scripts/check-linux-input-access.sh"
grep -q '@server=ibus' "$input_script" || {
    echo "fail  check-linux-input-access.sh does not wait for @server=ibus on XIM_SERVERS" >&2
    exit 1
}
grep -q 'engine_now.*==.*"hangul"' "$input_script" || {
    echo "fail  check-linux-input-access.sh does not verify ibus engine == hangul" >&2
    exit 1
}

fork_wf="$repo_root/.github/workflows/test-compose-from-fork.yml"
grep -q 'exit 1' "$fork_wf" || {
    echo "fail  test-compose-from-fork.yml does not fail when Compose builds differ" >&2
    exit 1
}

echo "ok    CR-15 ibus XIM readiness wait and fork equivalence failure gate"
