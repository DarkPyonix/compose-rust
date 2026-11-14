#!/usr/bin/env bash
# Fails if the quality gate stops being part of the one development CI run.
#
# Work lands on develop through pull requests. test.yml once limited its pull_request
# trigger to main, so those pull requests merged with no check while everyone assumed the
# gate had run. Development CI is now started only by a manual dispatch of ci.yml, on
# whatever ref it is dispatched for, and test.yml runs as a suite it calls. This keeps
# test.yml in that run and keeps it from growing a trigger, or a branch filter, of its own.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
wf="$repo_root/.github/workflows/test.yml"
ci="$repo_root/.github/workflows/ci.yml"

# The on: block runs from "on:" to the next top-level key.
on_block() { awk '/^on:/{f=1;next} /^[^ #]/{f=0} f' "$1"; }
test_on="$(on_block "$wf")"
ci_on="$(on_block "$ci")"

if ! grep -qE '^  workflow_dispatch:' <<<"$ci_on"; then
    echo "FAIL: ci.yml is not started by workflow_dispatch" >&2
    exit 1
fi
if ! grep -qE '^    uses: \./\.github/workflows/test\.yml$' "$ci"; then
    echo "FAIL: ci.yml does not call test.yml, so a dispatch runs no quality gate" >&2
    exit 1
fi
if ! grep -qE '^  workflow_call:' <<<"$test_on"; then
    echo "FAIL: test.yml is not a reusable workflow, so ci.yml cannot call it" >&2
    exit 1
fi
if grep -qE '^  (pull_request|pull_request_target|push|schedule|workflow_dispatch):' <<<"$test_on"; then
    echo "FAIL: test.yml has a trigger of its own; development CI is started only by ci.yml" >&2
    exit 1
fi
if grep -qE '^    branches' <<<"$test_on$ci_on"; then
    echo "FAIL: test.yml or ci.yml filters by branch, so some refs cannot be checked" >&2
    exit 1
fi
echo "ok: test.yml runs in every dispatched development CI run, on any ref"
