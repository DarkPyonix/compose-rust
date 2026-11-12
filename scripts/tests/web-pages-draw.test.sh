#!/usr/bin/env bash
# Usage: ./scripts/tests/web-pages-draw.test.sh
#
# The Samples test opens every sample's web page in a browser and fails when one does not
# draw.
#
# Building a page proves the page builds. notepad and todo built, packed and uploaded
# while each aborted in the browser before its first frame, and nothing said so until
# someone opened them by hand. `.github/scripts/check-sample-pages.mjs` is what would
# have; this checks that the workflow still runs it, on every sample, with a pinned
# browser, and that a failure fails the job.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

workflow=.github/workflows/test-samples.yml
script=.github/scripts/check-sample-pages.mjs

failures=0
fail() {
    failures=$((failures + 1))
    printf 'FAIL  %s\n' "$1" >&2
    shift
    local line
    for line in "$@"; do printf '        %s\n' "$line" >&2; done
}

# The job that runs the check: from the line naming the script back to the job's key, and
# forward to the next job.
job_key="$(awk -v script="$script" '
    /^  [a-z0-9_-]+:$/ { key = $1; sub(/:$/, "", key) }
    index($0, script) { print key; exit }
' "$workflow")"
if [[ -z "$job_key" ]]; then
    fail "$workflow does not run $script" \
        "Without it a sample that aborts in the browser ships with a page that never draws."
    exit "$failures"
fi
job="$(awk -v key="  $job_key:" '
    $0 == key { inside = 1; print; next }
    inside && /^  [a-z0-9_-]+:$/ { exit }
    inside { print }
' "$workflow")"

# It opens the pages the web job built, so it waits for that job.
if ! grep -qE '^    needs: .*\bweb\b' <<<"$job"; then
    fail "the $job_key job does not wait for the web job" \
        "It opens the samples-web archive that job uploads."
fi
if ! grep -q 'name: samples-web' <<<"$job"; then
    fail "the $job_key job does not download the samples-web archive"
fi

# Every application under samples/, by the rule build-sample-pages.sh builds by, not a
# list someone has to keep up to date.
if ! grep -qF 'samples/*/src/main.rs' <<<"$job"; then
    fail "the $job_key job does not open every sample" \
        "It has to take the samples from samples/*/src/main.rs, the rule" \
        "scripts/build-sample-pages.sh builds pages by, so a new sample is checked too."
fi
if ! grep -qF '[[ -f "samples/$sample/src/main.rs" ]] || continue' scripts/build-sample-pages.sh; then
    fail "scripts/build-sample-pages.sh no longer builds a page for each samples/*/src/main.rs" \
        "The page check and the page build have to agree on which samples there are."
fi
if ! grep -qF '"${samples[@]}"' <<<"$job"; then
    fail "the $job_key job does not pass the samples it found to the check"
fi

# A pinned Playwright, so a new browser cannot change the verdict on the same pages.
if ! grep -qE "PLAYWRIGHT_VERSION: ['\"]?[0-9]+\.[0-9]+\.[0-9]+['\"]?$" <<<"$job"; then
    fail "the $job_key job does not pin Playwright to an exact version" \
        "Set PLAYWRIGHT_VERSION to x.y.z in the job's env."
fi
if ! grep -qF 'playwright@$PLAYWRIGHT_VERSION' <<<"$job"; then
    fail "the $job_key job does not install the pinned Playwright"
fi

# A failure has to fail the run.
if grep -qE 'continue-on-error: *true' <<<"$job"; then
    fail "the $job_key job continues on error, so a page that does not draw passes"
fi

# The check itself fails on what it is there to catch.
if [[ ! -f "$script" ]]; then
    fail "$script is missing"
else
    grep -qF "'pageerror'" "$script" ||
        fail "$script does not listen for uncaught page errors" \
            "A Rust panic in the browser arrives as one (RuntimeError: unreachable)."
    grep -qF 'MIN_COLOURS' "$script" ||
        fail "$script does not check that the page drew something"
    grep -qF 'process.exit(1)' "$script" ||
        fail "$script never exits with a failure"
    grep -qF 'function fr22_web_every_sample_draws_without_a_page_error' "$script" ||
        fail "$script lost the function the SPEC's criterion names"
fi

if [[ "$failures" -eq 0 ]]; then
    echo "ok    the $job_key job opens every sample's page with a pinned Chromium and fails on one that does not draw"
fi
exit "$failures"
