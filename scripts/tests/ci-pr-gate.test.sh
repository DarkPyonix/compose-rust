#!/usr/bin/env bash
# Fails if the quality gate stops running on pull requests into develop.
#
# Work lands on develop through pull requests. test.yml once limited its pull_request
# trigger to main, so those pull requests merged with no check while everyone assumed the
# gate had run. This keeps the trigger open to every base branch.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
wf="$repo_root/.github/workflows/test.yml"

# The on: block runs from "on:" to the next top-level key.
on_block="$(awk '/^on:/{f=1;next} /^[^ #]/{f=0} f' "$wf")"

if ! grep -qE '^  pull_request:' <<<"$on_block"; then
    echo "FAIL: test.yml has no pull_request trigger" >&2
    exit 1
fi
if grep -qE '^    branches' <<<"$on_block"; then
    echo "FAIL: test.yml filters pull_request by base branch, so pull requests into develop are not checked" >&2
    exit 1
fi
echo "ok: test.yml runs on pull requests into any base branch"
